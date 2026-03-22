package com.github.ezzziy.codesandbox.executor;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.ezzziy.codesandbox.config.ExecutionConfig;
import com.github.ezzziy.codesandbox.exception.CompileException;
import com.github.ezzziy.codesandbox.exception.DangerousCodeException;
import com.github.ezzziy.codesandbox.model.dto.ExecutionResult;
import com.github.ezzziy.codesandbox.pool.ContainerPool;
import com.github.ezzziy.codesandbox.pool.PooledContainer;
import com.github.ezzziy.codesandbox.strategy.LanguageStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.ezzziy.codesandbox.common.enums.ExecutionStatus.SUCCESS;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerCodeExecutor {

    private final DockerClient dockerClient;
    private final ContainerManager containerManager;
    private final ContainerPool containerPool;
    private final ExecutionConfig executionConfig;

    @Value("${sandbox.pool.enabled:true}")
    private boolean poolEnabled;

    public record ExecuteResult(String compileOutput, List<ExecutionResult> results) {

    }

    public ExecuteResult execute(LanguageStrategy strategy,
                                 String code,
                                 List<String> inputList,
                                 String requestId,
                                 int timeLimit,
                                 int memoryLimit) {

        // 1. 危险代码扫描
        if (executionConfig.isEnableCodeScan()) {
            log.debug("检查危险代码: requestId={}", requestId);
            String dangerousPattern = strategy.checkDangerousCode(code);
            if (dangerousPattern != null) {
                log.warn("检测到危险代码: requestId={}, pattern={}", requestId, dangerousPattern);
                throw new DangerousCodeException(dangerousPattern, requestId);
            }
            log.debug("危险代码检查通过: requestId={}", requestId);
        }

        // 根据配置选择使用容器池或传统模式
        if (poolEnabled) {
            return executeWithPool(strategy, code, inputList, requestId, timeLimit, memoryLimit);
        } else {
            return executeTraditional(strategy, code, inputList, requestId, timeLimit, memoryLimit);
        }
    }

    /**
     * 使用容器池执行（高性能模式）
     * <p>
     * 容器生命周期：acquire(running) → execute → cleanup → release
     * <p>
     * 任务目录模型：
     * - 所有操作在 /sandbox/workspace/{jobId}/ 下执行
     * - 镜像已内置 sandbox 用户和目录结构，无需运行时初始化
     */
    private ExecuteResult executeWithPool(LanguageStrategy strategy,
                                          String code,
                                          List<String> inputList,
                                          String requestId,
                                          int timeLimit,
                                          int memoryLimit) {
        PooledContainer pooledContainer = null;
        String taskDir = null;  // 容器内任务工作目录路径
        String compileOutput = null;

        try {
            // 1. 从容器池获取容器（已处于 running 状态）
            pooledContainer = containerPool.acquireContainer(strategy);
            String containerId = pooledContainer.getContainerId();
            log.info("从容器池获取容器: requestId={}, containerId={}, useCount={}",
                    requestId, containerId.substring(0, 12), pooledContainer.getUseCount());

            // 2. 获取任务工作目录路径
            String jobId = "job-" + requestId;
            taskDir = containerManager.getTaskDirectory(jobId);

            // 3. 写源码到容器内任务子目录
            writeSourceCodeToContainer(containerId, taskDir, strategy.getSourceFileName(), code);

            // 4. 编译（在任务子目录内执行）
            if (strategy.needCompile()) {
                log.info("开始编译: requestId={}, sourceFile={}", requestId, strategy.getSourceFileName());
                CommandResult compileResult = compileInTaskDir(containerId, strategy, taskDir);
                compileOutput = buildCompileOutput(compileResult);

                if (!compileResult.isSuccess()) {
                    log.error("编译失败: requestId={}, time={}ms, exitCode={}, stderr={}",
                            requestId, compileResult.getExecutionTime(), compileResult.getExitCode(), compileResult.getStderr());
                    throw new CompileException(compileResult.getStderr(), requestId);
                }
                log.info("编译成功: requestId={}, time={}ms, output={}",
                        requestId, compileResult.getExecutionTime(),
                        compileOutput != null && compileOutput.length() > 100 ?
                                compileOutput.substring(0, 100) + "..." : compileOutput);
            } else {
                log.debug("无需编译: requestId={}, language={}", requestId, strategy.getLanguage());
            }

            // 5. 运行测试用例（在任务子目录内执行）
            log.info("开始运行测试用例: requestId={}, totalCount={}", requestId, inputList.size());
            List<ExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < inputList.size(); i++) {
                String input = inputList.get(i);
                log.debug("运行测试用例 #{}: requestId={}, inputLength={}",
                        i + 1, requestId, input != null ? input.length() : 0);

                ExecutionResult result = runCodeInTaskDir(
                        containerId,
                        strategy,
                        taskDir,
                        input,
                        i + 1,
                        timeLimit,
                        memoryLimit
                );
                results.add(result);

                log.info("测试用例 #{} 完成: requestId={}, status={}, time={}ms, memory={}KB",
                        i + 1, requestId, result.getStatus(), result.getTime(), result.getMemory());
                if (result.getStatus() != SUCCESS) {
                    log.warn("测试用例 #{} 异常: requestId={}, status={}, errorOutput={}",
                            i + 1, requestId, result.getStatus(),
                            result.getErrorOutput() != null ? result.getErrorOutput() : "无错误信息");
                }
            }
            log.info("所有测试用例执行完成: requestId={}, total={}", requestId, results.size());

            return new ExecuteResult(compileOutput, results);

        } finally {
            // 清理任务子目录，归还容器到池中
            if (pooledContainer != null) {
                String containerId = pooledContainer.getContainerId();
                // 先清理任务子目录
                if (taskDir != null) {
                    containerManager.cleanupTaskDirectory(containerId, taskDir);
                }
                // 再归还容器
                containerPool.releaseContainer(pooledContainer);
                log.debug("归还容器到池: requestId={}, containerId={}",
                        requestId, containerId);
            }
            log.info("执行完成，资源已清理: requestId={}", requestId);
        }
    }

    /**
     * 传统执行模式（创建并销毁容器）
     */
    private ExecuteResult executeTraditional(LanguageStrategy strategy,
                                            String code,
                                            List<String> inputList,
                                            String requestId,
                                            int timeLimit,
                                            int memoryLimit) {
        Path workDir = createWorkDirectory(requestId);
        String containerId = null;
        String compileOutput = null;

        try {
            // 2. 写源码
            writeSourceCode(workDir, strategy.getSourceFileName(), code);

            // 3. 创建并启动容器
            log.debug("创建容器: requestId={}, workDir={}", requestId, workDir);
            containerId = containerManager.createContainer(strategy, requestId, workDir.toString());
            log.debug("启动容器: requestId={}, containerId={}", requestId, containerId);
            containerManager.startContainer(containerId);
            log.info("容器启动成功: requestId={}, containerId={}", requestId, containerId);

            // 4. 编译
            if (strategy.needCompile()) {
                log.info("开始编译: requestId={}, sourceFile={}", requestId, strategy.getSourceFileName());
                CommandResult compileResult = compile(containerId, strategy);
                compileOutput = buildCompileOutput(compileResult);

                if (!compileResult.isSuccess()) {
                    log.error("编译失败: requestId={}, time={}ms, exitCode={}, stderr={}",
                            requestId, compileResult.getExecutionTime(), compileResult.getExitCode(), compileResult.getStderr());
                    throw new CompileException(compileResult.getStderr(), requestId);
                }
                log.info("编译成功: requestId={}, time={}ms, output={}",
                        requestId, compileResult.getExecutionTime(),
                        compileOutput != null && compileOutput.length() > 100 ?
                                compileOutput.substring(0, 100) + "..." : compileOutput);
            } else {
                log.debug("无需编译: requestId={}, language={}", requestId, strategy.getLanguage());
            }

            // 5. 运行
            log.info("开始运行测试用例: requestId={}, totalCount={}", requestId, inputList.size());
            List<ExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < inputList.size(); i++) {
                String input = inputList.get(i);
                log.debug("运行测试用例 #{}: requestId={}, inputLength={}",
                        i + 1, requestId, input != null ? input.length() : 0);

                ExecutionResult result = runCode(
                        containerId,
                        strategy,
                        input,
                        i + 1,
                        timeLimit,
                        memoryLimit
                );
                results.add(result);

                log.info("测试用例 #{} 完成: requestId={}, status={}, time={}ms, memory={}KB",
                        i + 1, requestId, result.getStatus(), result.getTime(), result.getMemory());
                if (result.getStatus() != SUCCESS) {
                    log.warn("测试用例 #{} 异常: requestId={}, status={}, errorOutput={}",
                            i + 1, requestId, result.getStatus(),
                            result.getErrorOutput() != null ? result.getErrorOutput() : "无错误信息");
                }
            }
            log.info("所有测试用例执行完成: requestId={}, total={}", requestId, results.size());

            return new ExecuteResult(compileOutput, results);

        } finally {
            log.debug("开始清理资源: requestId={}", requestId);
            if (containerId != null) {
                log.debug("移除容器: requestId={}, containerId={}", requestId, containerId);
                containerManager.removeContainer(containerId);
            }
            cleanupWorkDirectory(workDir);
            log.info("执行完成，资源已清理: requestId={}", requestId);
        }
    }

    /* ======================= 核心执行 ======================= */

    /**
     * 时间标记前缀，用于从输出中提取精确执行时间
     */
    private static final String TIME_MARKER = "__EXEC_TIME_NS__:";

    /**
     * 内存标记前缀，用于从输出中提取内存使用量
     */
    private static final String MEMORY_MARKER = "__EXEC_MEM_KB__:";

    private CommandResult executeCommand(String containerId,
                                         String[] cmd,
                                         String input,
                                         long timeoutMs) {
        log.debug("执行容器命令: containerId={}, cmd={}, hasInput={}, timeout={}ms",
                containerId, String.join(" ", cmd), input != null && !input.isEmpty(), timeoutMs);
        try {
            // 构建带精确计时和内存监测的命令
            // 使用 date +%s%N 获取纳秒级时间戳（在程序开始和结束时各记录一次）
            // 使用 /usr/bin/time 获取实际用户程序的内存使用量(maxrss)
            // 关键: 使用exec替换当前shell进程,这样/usr/bin/time统计的就是实际程序而非sh进程
            String originalCmd = String.join(" ", cmd);
            String timedCmd;
            
            if (input != null && !input.isEmpty()) {
                // 有输入：使用 printf 管道 + 计时包装 + 内存监测
                // 使用exec让用户程序替换shell进程,确保time统计的是用户程序而非shell
                // 使用-o参数将time的输出写入临时文件,避免与程序输出混淆
                // 使用EXIT_CODE=$?捕获真实退出码，最后exit $EXIT_CODE传递给Docker
                String escapedInput = escapeShellInput(input);
                timedCmd = String.format(
                        "MEMFILE=/tmp/mem_$$; " +
                        "START=$(date +%%s%%N); " +
                        "printf '%%s' '%s' | /usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; " +
                        "EXIT_CODE=$?; " +
                        "END=$(date +%%s%%N); " +
                        "echo '%s'$((END-START)); " +
                        "MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; " +
                        "echo '%s'$MEM; " +
                        "exit $EXIT_CODE",
                        escapedInput, originalCmd, TIME_MARKER, MEMORY_MARKER);
            } else {
                // 无输入：直接计时包装 + 内存监测
                // 使用-o参数将time的内存输出写入临时文件,避免吞掉stdout/stderr
                // 使用EXIT_CODE=$?捕获真实退出码，最后exit $EXIT_CODE传递给Docker
                timedCmd = String.format(
                        "MEMFILE=/tmp/mem_$$; " +
                        "START=$(date +%%s%%N); " +
                        "/usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; " +
                        "EXIT_CODE=$?; " +
                        "END=$(date +%%s%%N); " +
                        "echo '%s'$((END-START)); " +
                        "MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; " +
                        "echo '%s'$MEM; " +
                        "exit $EXIT_CODE",
                        originalCmd, TIME_MARKER, MEMORY_MARKER);
            }
            
            String[] actualCmd = new String[]{"sh", "-c", timedCmd};
            log.debug("使用计时命令: {}", timedCmd);

            // 以 sandbox 用户身份执行，实现沙箱隔离
            ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                    .withUser("sandbox")
                    .withCmd(actualCmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            String execId = execCreate.getId();
            log.debug("Exec 实例已创建: execId={}, containerId={}", execId, containerId);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            long javaStartTime = System.currentTimeMillis();

            ExecStartResultCallback callback = dockerClient.execStartCmd(execId)
                    .exec(new ExecStartResultCallback(stdout, stderr));

            boolean completed = callback.awaitCompletion(timeoutMs, TimeUnit.MILLISECONDS);
            long javaExecutionTime = System.currentTimeMillis() - javaStartTime;
            
            log.debug("命令执行完成状态: execId={}, completed={}, javaTime={}ms",
                    execId, completed, javaExecutionTime);

            if (!completed) {
                log.warn("命令执行超时: execId={}, containerId={}, timeout={}ms",
                        execId, containerId, timeoutMs);
                containerManager.killContainer(containerId);
                return CommandResult.timeout(timeoutMs);
            }

            InspectExecResponse inspectExec = dockerClient.inspectExecCmd(execId).exec();
            Long exitCodeLong = inspectExec.getExitCodeLong();
            int exitCode = exitCodeLong != null ? exitCodeLong.intValue() : -1;

            String stdoutStr = stdout.toString(StandardCharsets.UTF_8);
            String stderrStr = stderr.toString(StandardCharsets.UTF_8);
            
            // 从输出中提取精确执行时间和内存使用，并移除标记
            long executionTime = javaExecutionTime; // 默认使用 Java 层面时间
            long memoryUsage = 0; // 默认 0，如果无法提取则使用此值
            String cleanedOutput = stdoutStr;
            
            // 提取时间标记
            int timeMarkerIndex = stdoutStr.lastIndexOf(TIME_MARKER);
            if (timeMarkerIndex != -1) {
                // 提取时间标记之前的实际输出
                cleanedOutput = stdoutStr.substring(0, timeMarkerIndex);
                // 提取时间标记后面的纳秒时间
                String timeStr = stdoutStr.substring(timeMarkerIndex + TIME_MARKER.length());
                int memMarkerIndex = timeStr.indexOf(MEMORY_MARKER);
                if (memMarkerIndex != -1) {
                    timeStr = timeStr.substring(0, memMarkerIndex);
                }
                timeStr = timeStr.trim();
                try {
                    long nanoTime = Long.parseLong(timeStr);
                    executionTime = nanoTime / 1_000_000; // 纳秒转毫秒
                    log.debug("提取精确执行时间: nanoTime={}ns, executionTime={}ms, javaTime={}ms",
                            nanoTime, executionTime, javaExecutionTime);
                } catch (NumberFormatException e) {
                    log.warn("解析执行时间失败，使用 Java 层面时间: timeStr={}", timeStr);
                }
            }
            
            // 提取内存标记
            int memoryMarkerIndex = stdoutStr.lastIndexOf(MEMORY_MARKER);
            if (memoryMarkerIndex != -1) {
                String memStr = stdoutStr.substring(memoryMarkerIndex + MEMORY_MARKER.length()).trim();
                try {
                    memoryUsage = Long.parseLong(memStr);
                    log.debug("提取内存使用: {}KB", memoryUsage);
                } catch (NumberFormatException e) {
                    log.warn("解析内存使用失败: memStr={}", memStr);
                    memoryUsage = 0;
                }
            } else {
                log.warn("未找到内存标记，使用默认值 0");
            }
            
            log.debug("获取命令输出: execId={}, exitCode={}, stdoutLength={}, stderrLength={}, executionTime={}ms, memory={}KB",
                    execId, exitCode, cleanedOutput.length(), stderrStr.length(), executionTime, memoryUsage);

            int limit = executionConfig.getOutputLimit();
            if (cleanedOutput.length() > limit) {
                log.warn("输出超限: execId={}, outputLength={}, limit={}",
                        execId, cleanedOutput.length(), limit);
                return CommandResult.builder()
                        .success(false)
                        .outputExceeded(true)
                        .stdout(cleanedOutput.substring(0, limit))
                        .stderr(stderrStr)
                        .executionTime(executionTime)
                        .memoryUsage(memoryUsage)
                        .errorMessage("输出超限")
                        .build();
            }

            return exitCode == 0
                    ? CommandResult.success(cleanedOutput, stderrStr, executionTime, memoryUsage)
                    : CommandResult.failure(exitCode, cleanedOutput, stderrStr, executionTime, memoryUsage);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("命令执行被中断: containerId={}, cmd={}", containerId, String.join(" ", cmd), e);
            return CommandResult.error("执行被中断");
        } catch (Exception e) {
            log.error("执行命令失败: containerId={}, cmd={}, error={}",
                    containerId, String.join(" ", cmd), e.getMessage(), e);
            return CommandResult.error("执行失败: " + e.getMessage());
        }
    }

    /* ======================= 编译 / 运行 ======================= */

    /**
     * 在任务子目录中编译（容器池模式）
     */
    private CommandResult compileInTaskDir(String containerId, LanguageStrategy strategy, String taskDir) {
        String source = taskDir + "/" + strategy.getSourceFileName();
        String output = taskDir + "/" + strategy.getExecutableFileName();
        String[] compileCmd = strategy.getCompileCommand(source, output);
        
        log.debug("执行编译命令: containerId={}, taskDir={}, cmd={}, timeout={}s",
                containerId, taskDir, String.join(" ", compileCmd), strategy.getCompileTimeout());
        
        return executeCommandInDir(
                containerId,
                compileCmd,
                null,
                strategy.getCompileTimeout() * 1000L,
                taskDir
        );
    }

    /**
     * 在任务子目录中运行代码（容器池模式）
     */
    private ExecutionResult runCodeInTaskDir(String containerId,
                                              LanguageStrategy strategy,
                                              String taskDir,
                                              String input,
                                              int index,
                                              int timeLimit,
                                              int memoryLimit) {

        CommandResult result = executeCommandInDir(
                containerId,
                strategy.getRunCommand(taskDir + "/" + strategy.getExecutableFileName()),
                input,
                timeLimit,
                taskDir
        );

        // 优先使用容器内纳秒级精确时间，避免 Docker API 通信开销干扰
        long executionTime = result.getExecutionTime();

        // 1. 超时
        if (result.isTimeout()) {
            return ExecutionResult.timeout(index, timeLimit);
        }

        // 2. 内存超限
        if (result.isMemoryExceeded()) {
            return ExecutionResult.memoryExceeded(
                    index,
                    executionTime,
                    result.getMemoryUsage()
            );
        }

        // 3. 输出超限 → 运行时错误
        if (result.isOutputExceeded()) {
            return ExecutionResult.runtimeError(
                    index,
                    normalizeOutput(result.getStdout()),
                    "Output limit exceeded",
                    executionTime,
                    result.getMemoryUsage(),
                    result.getExitCode()
            );
        }

        // 4. 退出码非 0 → 运行时错误
        if (!result.isSuccess()) {
            return ExecutionResult.runtimeError(
                    index,
                    normalizeOutput(result.getStdout()),
                    result.getStderr(),
                    executionTime,
                    result.getMemoryUsage(),
                    result.getExitCode()
            );
        }

        // 5. 正常执行
        return ExecutionResult.success(
                index,
                normalizeOutput(result.getStdout()),
                result.getStderr(),
                executionTime,
                result.getMemoryUsage()
        );
    }

    /**
     * 在指定工作目录下执行命令（容器池模式）
     */
    private CommandResult executeCommandInDir(String containerId,
                                               String[] cmd,
                                               String input,
                                               long timeoutMs,
                                               String workDir) {
        log.debug("执行容器命令: containerId={}, workDir={}, cmd={}, hasInput={}, timeout={}ms",
                containerId, workDir, String.join(" ", cmd), input != null && !input.isEmpty(), timeoutMs);
        try {
            // 构建带精确计时和内存监测的命令，并切换到任务工作目录
            // 使用 /usr/bin/time 获取实际用户程序的内存使用量(maxrss)
            // 关键: 使用exec替换当前shell进程,这样/usr/bin/time统计的就是实际程序而非sh进程
            String originalCmd = String.join(" ", cmd);
            String timedCmd;
            
            // 先切换到任务工作目录，再执行命令
            String cdPrefix = "cd " + workDir + " && ";
            
            if (input != null && !input.isEmpty()) {
                // 有输入：使用 printf 管道 + 计时包装 + 内存监测
                // 使用exec让用户程序替换shell进程,确保time统计的是用户程序而非shell
                // 使用-o参数将time的输出写入临时文件,避免与程序输出混淆
                // 使用EXIT_CODE=$?捕获真实退出码，最后exit $EXIT_CODE传递给Docker
                String escapedInput = escapeShellInput(input);
                timedCmd = String.format(
                        "%sMEMFILE=/tmp/mem_$$; " +
                        "START=$(date +%%s%%N); " +
                        "printf '%%s' '%s' | /usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; " +
                        "EXIT_CODE=$?; " +
                        "END=$(date +%%s%%N); " +
                        "echo '%s'$((END-START)); " +
                        "MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; " +
                        "echo '%s'$MEM; " +
                        "exit $EXIT_CODE",
                        cdPrefix, escapedInput, originalCmd, TIME_MARKER, MEMORY_MARKER);
            } else {
                // 无输入：直接计时包装 + 内存监测
                // 使用-o参数将time的内存输出写入临时文件,避免吞掉stdout/stderr
                // 使用EXIT_CODE=$?捕获真实退出码，最后exit $EXIT_CODE传递给Docker
                timedCmd = String.format(
                        "%sMEMFILE=/tmp/mem_$$; " +
                        "START=$(date +%%s%%N); " +
                        "/usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; " +
                        "EXIT_CODE=$?; " +
                        "END=$(date +%%s%%N); " +
                        "echo '%s'$((END-START)); " +
                        "MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; " +
                        "echo '%s'$MEM; " +
                        "exit $EXIT_CODE",
                        cdPrefix, originalCmd, TIME_MARKER, MEMORY_MARKER);
            }
            
            String[] actualCmd = new String[]{"sh", "-c", timedCmd};
            log.debug("使用计时命令: {}", timedCmd);

            // 以 sandbox 用户身份执行，实现沙箱隔离
            ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                    .withUser("sandbox")
                    .withCmd(actualCmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            String execId = execCreate.getId();
            log.debug("Exec 实例已创建: execId={}, containerId={}", execId, containerId);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            long javaStartTime = System.currentTimeMillis();

            ExecStartResultCallback callback = dockerClient.execStartCmd(execId)
                    .exec(new ExecStartResultCallback(stdout, stderr));

            boolean completed = callback.awaitCompletion(timeoutMs, TimeUnit.MILLISECONDS);
            long javaExecutionTime = System.currentTimeMillis() - javaStartTime;
            
            log.debug("命令执行完成状态: execId={}, completed={}, javaTime={}ms",
                    execId, completed, javaExecutionTime);

            if (!completed) {
                log.warn("命令执行超时: execId={}, containerId={}, timeout={}ms",
                        execId, containerId, timeoutMs);
                containerManager.killContainer(containerId);
                return CommandResult.timeout(timeoutMs);
            }

            InspectExecResponse inspectExec = dockerClient.inspectExecCmd(execId).exec();
            Long exitCodeLong = inspectExec.getExitCodeLong();
            int exitCode = exitCodeLong != null ? exitCodeLong.intValue() : -1;

            String stdoutStr = stdout.toString(StandardCharsets.UTF_8);
            String stderrStr = stderr.toString(StandardCharsets.UTF_8);
            
            // 从输出中提取精确执行时间和内存使用，并移除标记
            long executionTime = javaExecutionTime;
            long memoryUsage = 0;
            String cleanedOutput = stdoutStr;
            
            // 提取时间标记
            int timeMarkerIndex = stdoutStr.lastIndexOf(TIME_MARKER);
            if (timeMarkerIndex != -1) {
                cleanedOutput = stdoutStr.substring(0, timeMarkerIndex);
                String timeStr = stdoutStr.substring(timeMarkerIndex + TIME_MARKER.length());
                int memMarkerIndex = timeStr.indexOf(MEMORY_MARKER);
                if (memMarkerIndex != -1) {
                    timeStr = timeStr.substring(0, memMarkerIndex);
                }
                timeStr = timeStr.trim();
                try {
                    long nanoTime = Long.parseLong(timeStr);
                    executionTime = nanoTime / 1_000_000;
                    log.debug("提取精确执行时间: nanoTime={}ns, executionTime={}ms, javaTime={}ms",
                            nanoTime, executionTime, javaExecutionTime);
                } catch (NumberFormatException e) {
                    log.warn("解析执行时间失败，使用 Java 层面时间: timeStr={}", timeStr);
                }
            }
            
            // 提取内存标记
            int memoryMarkerIndex = stdoutStr.lastIndexOf(MEMORY_MARKER);
            if (memoryMarkerIndex != -1) {
                String memStr = stdoutStr.substring(memoryMarkerIndex + MEMORY_MARKER.length()).trim();
                try {
                    memoryUsage = Long.parseLong(memStr);
                    log.debug("提取内存使用: {}KB", memoryUsage);
                } catch (NumberFormatException e) {
                    log.warn("解析内存使用失败: memStr={}", memStr);
                    memoryUsage = 0;
                }
            } else {
                log.warn("未找到内存标记，使用默认值 0");
            }
            
            log.debug("获取命令输出: execId={}, exitCode={}, stdoutLength={}, stderrLength={}, executionTime={}ms, memory={}KB",
                    execId, exitCode, cleanedOutput.length(), stderrStr.length(), executionTime, memoryUsage);

            int limit = executionConfig.getOutputLimit();
            if (cleanedOutput.length() > limit) {
                log.warn("输出超限: execId={}, outputLength={}, limit={}",
                        execId, cleanedOutput.length(), limit);
                return CommandResult.builder()
                        .success(false)
                        .outputExceeded(true)
                        .stdout(cleanedOutput.substring(0, limit))
                        .stderr(stderrStr)
                        .executionTime(executionTime)
                        .memoryUsage(memoryUsage)
                        .errorMessage("输出超限")
                        .build();
            }

            return exitCode == 0
                    ? CommandResult.success(cleanedOutput, stderrStr, executionTime, memoryUsage)
                    : CommandResult.failure(exitCode, cleanedOutput, stderrStr, executionTime, memoryUsage);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("命令执行被中断: containerId={}, cmd={}", containerId, String.join(" ", cmd), e);
            return CommandResult.error("执行被中断");
        } catch (Exception e) {
            log.error("执行命令失败: containerId={}, cmd={}, error={}",
                    containerId, String.join(" ", cmd), e.getMessage(), e);
            return CommandResult.error("执行失败: " + e.getMessage());
        }
    }

    /**
     * 在 /sandbox/workspace 目录下编译（传统模式）
     */
    private CommandResult compile(String containerId, LanguageStrategy strategy) {
        String source = "/sandbox/workspace/" + strategy.getSourceFileName();
        String output = "/sandbox/workspace/" + strategy.getExecutableFileName();
        String[] compileCmd = strategy.getCompileCommand(source, output);
        
        log.debug("执行编译命令: containerId={}, cmd={}, timeout={}s",
                containerId, String.join(" ", compileCmd), strategy.getCompileTimeout());
        
        return executeCommand(
                containerId,
                compileCmd,
                null,
                strategy.getCompileTimeout() * 1000L
        );
    }

    /**
     * 运行代码（传统模式）
     */
    private ExecutionResult runCode(String containerId,
                                    LanguageStrategy strategy,
                                    String input,
                                    int index,
                                    int timeLimit,
                                    int memoryLimit) {

        CommandResult result = executeCommand(
                containerId,
                strategy.getRunCommand("/sandbox/workspace/" + strategy.getExecutableFileName()),
                input,
                timeLimit
        );

        // 优先使用容器内纳秒级精确时间，避免 Docker API 通信开销干扰
        long executionTime = result.getExecutionTime();

        // 1. 超时
        if (result.isTimeout()) {
            return ExecutionResult.timeout(index, timeLimit);
        }

        // 2. 内存超限
        if (result.isMemoryExceeded()) {
            return ExecutionResult.memoryExceeded(
                    index,
                    executionTime,
                    result.getMemoryUsage()
            );
        }

        // 3. 输出超限 → 运行时错误
        if (result.isOutputExceeded()) {
            return ExecutionResult.runtimeError(
                    index,
                    normalizeOutput(result.getStdout()),
                    "Output limit exceeded",
                    executionTime,
                    result.getMemoryUsage(),
                    result.getExitCode()
            );
        }

        // 4. 退出码非 0 → 运行时错误
        if (!result.isSuccess()) {
            return ExecutionResult.runtimeError(
                    index,
                    normalizeOutput(result.getStdout()),
                    result.getStderr(),
                    executionTime,
                    result.getMemoryUsage(),
                    result.getExitCode()
            );
        }

        // 5. 正常执行
        return ExecutionResult.success(
                index,
                normalizeOutput(result.getStdout()),
                result.getStderr(),
                executionTime,
                result.getMemoryUsage()
        );
    }




    /* ======================= 文件 & 工具 ======================= */

    private void writeSourceCodeToContainer(String containerId, String taskDir, String fileName, String code) {
        try {
            // 通过 exec 在容器内部完成 mkdir + 写文件，绕过 readonlyRootfs 对 Docker archive API 的限制
            // tmpfs 从容器内部视角可写，exec 在容器内执行，因此不受 readonlyRootfs 影响
            byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
            String base64Code = Base64.getEncoder().encodeToString(codeBytes);
            String filePath = taskDir + "/" + fileName;

            // 合并 mkdir 和写文件为 1 次 exec 调用
            String writeCmd = "mkdir -p " + taskDir + " && echo '" + base64Code + "' | base64 -d > " + filePath;

            ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                    .withUser("sandbox")
                    .withCmd("sh", "-c", writeCmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            dockerClient.execStartCmd(execCreate.getId())
                    .exec(new ExecStartResultCallback(stdout, stderr))
                    .awaitCompletion(10, TimeUnit.SECONDS);

            InspectExecResponse inspect = dockerClient.inspectExecCmd(execCreate.getId()).exec();
            if (inspect.getExitCodeLong() != null && inspect.getExitCodeLong() != 0) {
                throw new RuntimeException("写入文件失败: exitCode=" + inspect.getExitCodeLong()
                        + ", stderr=" + stderr.toString(StandardCharsets.UTF_8));
            }

            log.debug("源码写入成功: containerId={}, path={}", containerId.substring(0, 12), filePath);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("写入源码到容器发生致命异常: containerId={}, taskDir={}, error={}",
                    containerId, taskDir, e.getMessage());
            throw new RuntimeException("写入源码到容器失败", e);
        }
    }

    /**
     * 创建宿主机工作目录（传统模式使用）
     */
    private Path createWorkDirectory(String requestId) {
        try {
            Path dir = Path.of(executionConfig.getWorkDir(), "exec-" + requestId);
            Files.createDirectories(dir);
            // 不再设置权限，依赖容器内的用户归属模型
            return dir;
        } catch (IOException e) {
            throw new RuntimeException("创建工作目录失败", e);
        }
    }

    /**
     * 写入源码到宿主机目录（传统模式使用）
     */
    private void writeSourceCode(Path workDir, String fileName, String code) {
        try {
            Path file = workDir.resolve(fileName);
            Files.writeString(file, code, StandardCharsets.UTF_8);
            // 不再设置权限，依赖容器内的用户归属模型
        } catch (IOException e) {
            throw new RuntimeException("写入源代码失败", e);
        }
    }

    private void cleanupWorkDirectory(Path workDir) {
        try {
            FileUtils.deleteDirectory(workDir.toFile());
        } catch (IOException e) {
            log.warn("清理目录失败: {}", workDir, e);
        }
    }

    private String buildCompileOutput(CommandResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.getStdout() != null && !result.getStdout().isBlank()) {
            sb.append(result.getStdout().trim());
        }
        if (result.getStderr() != null && !result.getStderr().isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(result.getStderr().trim());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String normalizeOutput(String output) {
        if (output == null) return "";
        return output.trim()
                .replace("\r\n", "\n")
                .replace("\r", "\n");
    }

    /**
     * 转义 shell 单引号字符串中的特殊字符
     * 单引号内唯一需要处理的是单引号本身
     */
    private String escapeShellInput(String input) {
        if (input == null) return "";
        // 在单引号字符串中，单引号需要特殊处理：'text'\''more'
        // 即：结束当前单引号，添加转义的单引号，再开始新的单引号
        return input.replace("'", "'\\''");
    }
}
