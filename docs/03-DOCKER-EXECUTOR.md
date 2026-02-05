# OJ 代码执行沙箱服务 - Docker 执行器核心实现

## 1. 概述

Docker 执行器是代码沙箱的核心组件，负责：
- 创建隔离的 Docker 容器
- 在容器中编译和执行用户代码
- 采集执行结果（时间、内存、输出）
- 确保容器资源限制和安全隔离
- 清理临时资源

---

## 2. Docker 配置

### 2.1 DockerConfig

```java
package com.github.ezzziy.codesandbox.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sandbox.docker")
public class DockerConfig {

    /**
     * Docker Host
     * Linux: unix:///var/run/docker.sock
     * Windows: tcp://localhost:2375 或 npipe:////./pipe/docker_engine
     */
    private String host = "unix:///var/run/docker.sock";
    
    /**
     * Docker API 版本
     */
    private String apiVersion = "1.41";
    
    /**
     * 连接超时（毫秒）
     */
    private Integer connectionTimeout = 30000;
    
    /**
     * 读取超时（毫秒）
     */
    private Integer readTimeout = 60000;

    @Bean
    public DockerClientConfig dockerClientConfig() {
        return DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(host)
                .withApiVersion(apiVersion)
                .build();
    }

    @Bean
    public DockerHttpClient dockerHttpClient(DockerClientConfig config) {
        return new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .maxConnections(100)
                .connectionTimeout(Duration.ofMillis(connectionTimeout))
                .responseTimeout(Duration.ofMillis(readTimeout))
                .build();
    }

    @Bean
    public DockerClient dockerClient(DockerClientConfig config, DockerHttpClient httpClient) {
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
```

### 2.2 执行限制配置

```java
package com.github.ezzziy.codesandbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sandbox.execution")
public class ExecutionConfig {

    /**
     * 默认时间限制（毫秒）
     */
    private Integer defaultTimeLimit = 5000;

    /**
     * 默认内存限制（MB）
     */
    private Integer defaultMemoryLimit = 256;

    /**
     * 最大时间限制（毫秒）
     */
    private Integer maxTimeLimit = 30000;

    /**
     * 最大内存限制（MB）
     */
    private Integer maxMemoryLimit = 512;

    /**
     * 最大输出大小（字节）
     */
    private Integer maxOutputSize = 65536;  // 64KB

    /**
     * 最大进程数
     */
    private Integer maxProcessCount = 10;

    /**
     * 编译超时时间（毫秒）
     */
    private Integer compileTimeout = 30000;

    /**
     * 容器启动超时（秒）
     */
    private Integer containerStartTimeout = 10;
}
```

---

## 3. 容器管理器

### 3.1 ContainerManager 接口

```java
package com.github.ezzziy.codesandbox.docker;

import com.github.ezzziy.codesandbox.model.dto.ContainerConfig;
import com.github.ezzziy.codesandbox.model.dto.ExecResult;

public interface ContainerManager {

    /**
     * 创建容器
     * @param config 容器配置
     * @return 容器 ID
     */
    String createContainer(ContainerConfig config);

    /**
     * 启动容器
     * @param containerId 容器 ID
     */
    void startContainer(String containerId);

    /**
     * 在容器中执行命令
     * @param containerId 容器 ID
     * @param command 命令
     * @param timeout 超时时间（毫秒）
     * @return 执行结果
     */
    ExecResult execInContainer(String containerId, String[] command, long timeout);

    /**
     * 复制文件到容器
     * @param containerId 容器 ID
     * @param localPath 本地路径
     * @param containerPath 容器内路径
     */
    void copyToContainer(String containerId, String localPath, String containerPath);

    /**
     * 从容器复制文件
     * @param containerId 容器 ID
     * @param containerPath 容器内路径
     * @param localPath 本地路径
     */
    void copyFromContainer(String containerId, String containerPath, String localPath);

    /**
     * 获取容器资源使用情况
     * @param containerId 容器 ID
     * @return 内存使用量（字节）
     */
    long getMemoryUsage(String containerId);

    /**
     * 停止容器
     * @param containerId 容器 ID
     */
    void stopContainer(String containerId);

    /**
     * 删除容器
     * @param containerId 容器 ID
     */
    void removeContainer(String containerId);

    /**
     * 强制清理容器（停止 + 删除）
     * @param containerId 容器 ID
     */
    void forceCleanup(String containerId);
}
```

### 3.2 ContainerManager 实现

```java
package com.github.ezzziy.codesandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.ezzziy.codesandbox.config.ExecutionConfig;
import com.github.ezzziy.codesandbox.exception.ContainerException;
import com.github.ezzziy.codesandbox.model.dto.ContainerConfig;
import com.github.ezzziy.codesandbox.model.dto.ExecResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerContainerManager implements ContainerManager {

    private final DockerClient dockerClient;
    private final ExecutionConfig executionConfig;

    @Override
    public String createContainer(ContainerConfig config) {
        try {
            // 构建 HostConfig（资源限制）
            HostConfig hostConfig = HostConfig.newHostConfig()
                    // 内存限制
                    .withMemory(config.getMemoryLimit() * 1024 * 1024L)
                    .withMemorySwap(config.getMemoryLimit() * 1024 * 1024L)  // 禁用 swap
                    // CPU 限制（纳秒，100000 = 0.1 核）
                    .withCpuQuota(100000L)
                    .withCpuPeriod(100000L)
                    // 进程数限制（防止 fork bomb）
                    .withPidsLimit((long) executionConfig.getMaxProcessCount())
                    // 禁用网络
                    .withNetworkMode(config.getEnableNetwork() ? "bridge" : "none")
                    // 只读根文件系统
                    .withReadonlyRootfs(true)
                    // 挂载临时工作目录（可写）
                    .withBinds(new Bind(config.getWorkDir(), new Volume("/workspace")))
                    // 临时文件系统（可写的 /tmp）
                    .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=64m"))
                    // 安全选项
                    .withSecurityOpts(List.of("no-new-privileges"))
                    // 禁用所有 capabilities
                    .withCapDrop(Capability.ALL)
                    // OOM 设置
                    .withOomKillDisable(false);

            CreateContainerCmd createCmd = dockerClient.createContainerCmd(config.getImage())
                    .withHostConfig(hostConfig)
                    .withWorkingDir("/workspace")
                    .withUser("nobody")  // 非 root 用户运行
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(false)
                    .withStdinOpen(true)
                    .withCmd("/bin/sh", "-c", "sleep infinity");  // 保持容器运行

            // 设置环境变量
            if (config.getEnvVars() != null && !config.getEnvVars().isEmpty()) {
                createCmd.withEnv(config.getEnvVars().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .toList());
            }

            CreateContainerResponse response = createCmd.exec();
            String containerId = response.getId();
            
            log.debug("容器创建成功: containerId={}, image={}", 
                    containerId.substring(0, 12), config.getImage());
            
            return containerId;
            
        } catch (Exception e) {
            log.error("创建容器失败: image={}", config.getImage(), e);
            throw new ContainerException("创建容器失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void startContainer(String containerId) {
        try {
            dockerClient.startContainerCmd(containerId).exec();
            
            // 等待容器启动
            int maxWait = executionConfig.getContainerStartTimeout();
            for (int i = 0; i < maxWait; i++) {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
                if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                    log.debug("容器启动成功: containerId={}", containerId.substring(0, 12));
                    return;
                }
                Thread.sleep(100);
            }
            
            throw new ContainerException("容器启动超时");
            
        } catch (ContainerException e) {
            throw e;
        } catch (Exception e) {
            log.error("启动容器失败: containerId={}", containerId, e);
            throw new ContainerException("启动容器失败: " + e.getMessage(), e);
        }
    }

    @Override
    public ExecResult execInContainer(String containerId, String[] command, long timeout) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 创建执行实例
            ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                    .withCmd(command)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withAttachStdin(false)
                    .exec();

            String execId = execCreate.getId();

            // 收集输出
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            
            CompletableFuture<Void> future = new CompletableFuture<>();

            dockerClient.execStartCmd(execId)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            try {
                                byte[] payload = frame.getPayload();
                                if (frame.getStreamType() == StreamType.STDOUT) {
                                    stdout.write(payload);
                                } else if (frame.getStreamType() == StreamType.STDERR) {
                                    stderr.write(payload);
                                }
                                
                                // 检查输出大小限制
                                if (stdout.size() + stderr.size() > executionConfig.getMaxOutputSize()) {
                                    log.warn("输出超限，强制终止: execId={}", execId);
                                    close();
                                }
                            } catch (IOException e) {
                                log.error("写入输出失败", e);
                            }
                        }

                        @Override
                        public void onComplete() {
                            future.complete(null);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            future.completeExceptionally(throwable);
                        }
                    });

            // 等待执行完成（带超时）
            try {
                future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("执行超时: containerId={}, timeout={}ms", containerId.substring(0, 12), timeout);
                return ExecResult.builder()
                        .stdout(truncateOutput(stdout.toString(StandardCharsets.UTF_8)))
                        .stderr("Time Limit Exceeded")
                        .exitCode(-1)
                        .timeUsed(timeout)
                        .timedOut(true)
                        .build();
            }

            // 获取退出码
            Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            long timeUsed = System.currentTimeMillis() - startTime;

            return ExecResult.builder()
                    .stdout(truncateOutput(stdout.toString(StandardCharsets.UTF_8)))
                    .stderr(truncateOutput(stderr.toString(StandardCharsets.UTF_8)))
                    .exitCode(exitCode != null ? exitCode.intValue() : -1)
                    .timeUsed(timeUsed)
                    .timedOut(false)
                    .build();

        } catch (Exception e) {
            log.error("容器内执行命令失败: containerId={}", containerId, e);
            long timeUsed = System.currentTimeMillis() - startTime;
            return ExecResult.builder()
                    .stdout("")
                    .stderr("Execution failed: " + e.getMessage())
                    .exitCode(-1)
                    .timeUsed(timeUsed)
                    .timedOut(false)
                    .build();
        }
    }

    @Override
    public void copyToContainer(String containerId, String localPath, String containerPath) {
        try {
            Path path = Path.of(localPath);
            
            // 创建 tar 归档
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(baos)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                
                if (Files.isDirectory(path)) {
                    // 递归添加目录
                    Files.walk(path).forEach(file -> {
                        try {
                            String entryName = path.relativize(file).toString();
                            if (entryName.isEmpty()) return;
                            
                            TarArchiveEntry entry = new TarArchiveEntry(file.toFile(), entryName);
                            tar.putArchiveEntry(entry);
                            
                            if (Files.isRegularFile(file)) {
                                Files.copy(file, tar);
                            }
                            tar.closeArchiveEntry();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                } else {
                    // 单个文件
                    TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), path.getFileName().toString());
                    tar.putArchiveEntry(entry);
                    Files.copy(path, tar);
                    tar.closeArchiveEntry();
                }
            }
            
            // 复制到容器
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(baos.toByteArray()))
                    .withRemotePath(containerPath)
                    .exec();
                    
            log.debug("文件复制到容器: {} -> {}:{}", localPath, containerId.substring(0, 12), containerPath);
            
        } catch (Exception e) {
            log.error("复制文件到容器失败: containerId={}, path={}", containerId, localPath, e);
            throw new ContainerException("复制文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void copyFromContainer(String containerId, String containerPath, String localPath) {
        try (InputStream is = dockerClient.copyArchiveFromContainerCmd(containerId, containerPath).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(is)) {
            
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path outputPath = Path.of(localPath, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                } else {
                    Files.createDirectories(outputPath.getParent());
                    try (OutputStream os = Files.newOutputStream(outputPath)) {
                        IOUtils.copy(tar, os);
                    }
                }
            }
            
            log.debug("从容器复制文件: {}:{} -> {}", containerId.substring(0, 12), containerPath, localPath);
            
        } catch (Exception e) {
            log.error("从容器复制文件失败: containerId={}, path={}", containerId, containerPath, e);
            throw new ContainerException("复制文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public long getMemoryUsage(String containerId) {
        try {
            Statistics[] stats = {null};
            CountDownLatch latch = new CountDownLatch(1);
            
            dockerClient.statsCmd(containerId).withNoStream(true)
                    .exec(new ResultCallback.Adapter<Statistics>() {
                        @Override
                        public void onNext(Statistics statistics) {
                            stats[0] = statistics;
                            latch.countDown();
                        }
                    });
            
            if (latch.await(5, TimeUnit.SECONDS) && stats[0] != null) {
                return stats[0].getMemoryStats().getUsage();
            }
            return 0;
        } catch (Exception e) {
            log.warn("获取容器内存使用失败: containerId={}", containerId, e);
            return 0;
        }
    }

    @Override
    public void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(1).exec();
            log.debug("容器已停止: containerId={}", containerId.substring(0, 12));
        } catch (Exception e) {
            log.warn("停止容器失败（可能已停止）: containerId={}", containerId);
        }
    }

    @Override
    public void removeContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            log.debug("容器已删除: containerId={}", containerId.substring(0, 12));
        } catch (Exception e) {
            log.warn("删除容器失败: containerId={}", containerId);
        }
    }

    @Override
    public void forceCleanup(String containerId) {
        if (containerId == null) return;
        try {
            stopContainer(containerId);
        } finally {
            removeContainer(containerId);
        }
    }

    /**
     * 截断过长的输出
     */
    private String truncateOutput(String output) {
        int maxSize = executionConfig.getMaxOutputSize();
        if (output.length() > maxSize) {
            return output.substring(0, maxSize) + "\n... [output truncated]";
        }
        return output;
    }
}
```

---

## 4. Docker 执行器

### 4.1 CodeExecutor 接口

```java
package com.github.ezzziy.codesandbox.executor;

import com.github.ezzziy.codesandbox.model.dto.ExecutionContext;
import com.github.ezzziy.codesandbox.model.response.ExecutionResult;

public interface CodeExecutor {

    /**
     * 执行代码
     * @param context 执行上下文
     * @return 执行结果
     */
    ExecutionResult execute(ExecutionContext context);
}
```

### 4.2 ExecutionContext 上下文

```java
package com.github.ezzziy.codesandbox.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExecutionContext {
    
    /**
     * 执行 ID
     */
    private String executionId;
    
    /**
     * 语言
     */
    private String language;
    
    /**
     * 语言版本
     */
    private String languageVersion;
    
    /**
     * 用户代码
     */
    private String code;
    
    /**
     * 输入数据
     */
    private String inputData;
    
    /**
     * 时间限制（毫秒）
     */
    private Integer timeLimit;
    
    /**
     * 内存限制（MB）
     */
    private Integer memoryLimit;
    
    /**
     * 是否启用网络
     */
    private Boolean enableNetwork;
    
    /**
     * 工作目录
     */
    private String workDir;
}
```

### 4.3 DockerCodeExecutor 实现

```java
package com.github.ezzziy.codesandbox.executor;

import com.github.ezzziy.codesandbox.config.ExecutionConfig;
import com.github.ezzziy.codesandbox.docker.ContainerManager;
import com.github.ezzziy.codesandbox.executor.strategy.LanguageStrategy;
import com.github.ezzziy.codesandbox.executor.strategy.LanguageStrategyFactory;
import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import com.github.ezzziy.codesandbox.model.response.ExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerCodeExecutor implements CodeExecutor {

    private final ContainerManager containerManager;
    private final LanguageStrategyFactory strategyFactory;
    private final ExecutionConfig executionConfig;

    @Override
    public ExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        String containerId = null;
        Path workDir = null;

        try {
            // 1. 获取语言策略
            LanguageStrategy strategy = strategyFactory.getStrategy(
                    context.getLanguage(),
                    context.getLanguageVersion()
            );

            // 2. 创建临时工作目录
            workDir = createWorkDir(context.getExecutionId());
            context.setWorkDir(workDir.toString());

            // 3. 写入源代码文件
            writeSourceCode(workDir, strategy.getSourceFileName(), context.getCode());

            // 4. 写入输入数据
            writeInputData(workDir, context.getInputData());

            // 5. 创建并启动容器
            ContainerConfig containerConfig = ContainerConfig.builder()
                    .image(strategy.getDockerImage())
                    .workDir(workDir.toString())
                    .memoryLimit(context.getMemoryLimit())
                    .enableNetwork(context.getEnableNetwork())
                    .build();

            containerId = containerManager.createContainer(containerConfig);
            containerManager.startContainer(containerId);

            // 6. 编译（如果需要）
            CompileResult compileResult = null;
            if (strategy.needsCompilation()) {
                compileResult = compile(containerId, strategy, context);
                if (!compileResult.getSuccess()) {
                    return ExecutionResult.builder()
                            .status(ExecutionStatus.COMPILE_ERROR)
                            .compileResult(compileResult)
                            .executionId(context.getExecutionId())
                            .totalTime(System.currentTimeMillis() - startTime)
                            .build();
                }
            }

            // 7. 执行
            RunResult runResult = run(containerId, strategy, context);

            // 8. 确定执行状态
            ExecutionStatus status = determineStatus(runResult, context);

            return ExecutionResult.builder()
                    .status(status)
                    .compileResult(compileResult)
                    .runResult(runResult)
                    .executionId(context.getExecutionId())
                    .totalTime(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.error("执行失败: executionId={}", context.getExecutionId(), e);
            return ExecutionResult.builder()
                    .status(ExecutionStatus.SYSTEM_ERROR)
                    .errorMessage(e.getMessage())
                    .executionId(context.getExecutionId())
                    .totalTime(System.currentTimeMillis() - startTime)
                    .build();
        } finally {
            // 9. 清理资源
            cleanup(containerId, workDir);
        }
    }

    /**
     * 编译代码
     */
    private CompileResult compile(String containerId, LanguageStrategy strategy, ExecutionContext context) {
        log.debug("开始编译: executionId={}, language={}", context.getExecutionId(), context.getLanguage());

        String[] compileCommand = strategy.getCompileCommand(context);
        long compileTimeout = executionConfig.getCompileTimeout();

        ExecResult execResult = containerManager.execInContainer(containerId, compileCommand, compileTimeout);

        boolean success = execResult.getExitCode() == 0 && !execResult.getTimedOut();

        return CompileResult.builder()
                .success(success)
                .output(execResult.getStdout())
                .errorOutput(execResult.getStderr())
                .timeUsed(execResult.getTimeUsed())
                .build();
    }

    /**
     * 执行代码
     */
    private RunResult run(String containerId, LanguageStrategy strategy, ExecutionContext context) {
        log.debug("开始执行: executionId={}, timeLimit={}ms", context.getExecutionId(), context.getTimeLimit());

        // 构建执行命令（带输入重定向）
        String[] runCommand = strategy.getRunCommand(context);

        // 实际的执行命令需要处理输入重定向
        String[] wrappedCommand = wrapWithInputRedirect(runCommand);

        ExecResult execResult = containerManager.execInContainer(
                containerId,
                wrappedCommand,
                context.getTimeLimit() + 1000  // 额外 1 秒缓冲
        );

        // 获取内存使用
        long memoryUsed = containerManager.getMemoryUsage(containerId) / 1024;  // 转为 KB

        // 解析退出信号
        String signal = parseSignal(execResult.getExitCode());

        return RunResult.builder()
                .stdout(execResult.getStdout())
                .stderr(execResult.getStderr())
                .exitCode(execResult.getExitCode())
                .timeUsed(execResult.getTimeUsed())
                .memoryUsed(memoryUsed)
                .signal(signal)
                .build();
    }

    /**
     * 确定执行状态
     */
    private ExecutionStatus determineStatus(RunResult runResult, ExecutionContext context) {
        // 超时
        if (runResult.getTimeUsed() >= context.getTimeLimit()) {
            return ExecutionStatus.TIME_LIMIT_EXCEEDED;
        }

        // 内存超限
        if (runResult.getMemoryUsed() > context.getMemoryLimit() * 1024L) {
            return ExecutionStatus.MEMORY_LIMIT_EXCEEDED;
        }

        // 运行时错误（非零退出码）
        if (runResult.getExitCode() != 0) {
            // 特殊信号处理
            if (runResult.getSignal() != null) {
                if ("SIGKILL".equals(runResult.getSignal()) &&
                        runResult.getMemoryUsed() > context.getMemoryLimit() * 1024L * 0.9) {
                    return ExecutionStatus.MEMORY_LIMIT_EXCEEDED;
                }
            }
            return ExecutionStatus.RUNTIME_ERROR;
        }

        return ExecutionStatus.ACCEPTED;
    }

    /**
     * 包装命令以支持输入重定向
     */
    private String[] wrapWithInputRedirect(String[] command) {
        String cmd = String.join(" ", command);
        return new String[]{"/bin/sh", "-c", cmd + " < /workspace/input.txt"};
    }

    /**
     * 解析退出信号
     */
    private String parseSignal(int exitCode) {
        if (exitCode <= 0) return null;
        if (exitCode > 128) {
            int signal = exitCode - 128;
            return switch (signal) {
                case 9 -> "SIGKILL";
                case 11 -> "SIGSEGV";
                case 6 -> "SIGABRT";
                case 8 -> "SIGFPE";
                case 15 -> "SIGTERM";
                default -> "SIG" + signal;
            };
        }
        return null;
    }

    /**
     * 创建工作目录
     */
    private Path createWorkDir(String executionId) throws IOException {
        Path baseDir = Path.of(System.getProperty("java.io.tmpdir"), "sandbox", executionId);
        Files.createDirectories(baseDir);
        return baseDir;
    }

    /**
     * 写入源代码文件
     */
    private void writeSourceCode(Path workDir, String fileName, String code) throws IOException {
        Path sourceFile = workDir.resolve(fileName);
        Files.writeString(sourceFile, code, StandardCharsets.UTF_8);
    }

    /**
     * 写入输入数据
     */
    private void writeInputData(Path workDir, String inputData) throws IOException {
        Path inputFile = workDir.resolve("input.txt");
        Files.writeString(inputFile, inputData != null ? inputData : "", StandardCharsets.UTF_8);
    }

    /**
     * 清理资源
     */
    private void cleanup(String containerId, Path workDir) {
        // 清理容器
        if (containerId != null) {
            try {
                containerManager.forceCleanup(containerId);
            } catch (Exception e) {
                log.warn("清理容器失败: containerId={}", containerId);
            }
        }

        // 清理工作目录
        if (workDir != null) {
            try {
                deleteRecursively(workDir);
            } catch (Exception e) {
                log.warn("清理工作目录失败: workDir={}", workDir);
            }
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
```

---

## 5. Docker 命令示例

### 5.1 完整的 Docker Run 命令

```bash
# C/C++ 执行示例
docker run --rm \
  --name sandbox-exec-uuid \
  --memory=256m \
  --memory-swap=256m \
  --cpus=0.5 \
  --pids-limit=10 \
  --network=none \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --security-opt=no-new-privileges \
  --cap-drop=ALL \
  --user=nobody \
  -v /tmp/sandbox/work:/workspace:rw \
  -w /workspace \
  gcc:11-bullseye \
  /bin/sh -c "g++ -O2 -std=c++11 -o main main.cpp && timeout 5 ./main < input.txt"

# Java 执行示例
docker run --rm \
  --name sandbox-exec-uuid \
  --memory=512m \
  --memory-swap=512m \
  --cpus=1.0 \
  --pids-limit=50 \
  --network=none \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --security-opt=no-new-privileges \
  --cap-drop=ALL \
  --user=nobody \
  -v /tmp/sandbox/work:/workspace:rw \
  -w /workspace \
  openjdk:11-jdk-slim \
  /bin/sh -c "javac -encoding UTF-8 Main.java && timeout 10 java -Xmx256m Main < input.txt"

# Python 执行示例
docker run --rm \
  --name sandbox-exec-uuid \
  --memory=256m \
  --memory-swap=256m \
  --cpus=0.5 \
  --pids-limit=10 \
  --network=none \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --security-opt=no-new-privileges \
  --cap-drop=ALL \
  --user=nobody \
  -v /tmp/sandbox/work:/workspace:rw \
  -w /workspace \
  python:3.10-slim \
  /bin/sh -c "timeout 5 python3 main.py < input.txt"
```

### 5.2 参数说明

| 参数 | 说明 |
|------|------|
| `--rm` | 容器退出后自动删除 |
| `--memory=256m` | 内存限制 256MB |
| `--memory-swap=256m` | 禁用 swap（等于内存限制） |
| `--cpus=0.5` | CPU 限制 0.5 核 |
| `--pids-limit=10` | 最大进程数 10（防止 fork bomb） |
| `--network=none` | 禁用网络 |
| `--read-only` | 只读根文件系统 |
| `--tmpfs /tmp:...` | 可写的临时目录 |
| `--security-opt=no-new-privileges` | 禁止提权 |
| `--cap-drop=ALL` | 删除所有 capabilities |
| `--user=nobody` | 非 root 用户运行 |
| `-v .../workspace:rw` | 挂载工作目录 |
| `timeout 5` | 执行超时 5 秒 |

---

## 6. 资源监控与采集

### 6.1 时间测量

```java
/**
 * 精确的执行时间测量
 */
public class TimeMonitor {
    
    private long startTime;
    private long endTime;
    
    public void start() {
        this.startTime = System.nanoTime();
    }
    
    public void stop() {
        this.endTime = System.nanoTime();
    }
    
    /**
     * 获取执行时间（毫秒）
     */
    public long getTimeUsed() {
        return (endTime - startTime) / 1_000_000;
    }
    
    /**
     * 获取执行时间（微秒，用于高精度场景）
     */
    public long getTimeUsedMicros() {
        return (endTime - startTime) / 1_000;
    }
}
```

### 6.2 内存采集

```java
/**
 * 通过 Docker Stats API 采集内存使用
 */
public long collectMemoryUsage(String containerId) {
    try {
        // 使用 docker stats --no-stream 获取单次统计
        Statistics stats = dockerClient.statsCmd(containerId)
                .withNoStream(true)
                .exec(new InvocationBuilder.AsyncResultCallback<Statistics>())
                .awaitResult(5, TimeUnit.SECONDS);
        
        if (stats != null && stats.getMemoryStats() != null) {
            // 返回实际使用的内存（不含缓存）
            Long usage = stats.getMemoryStats().getUsage();
            Long cache = stats.getMemoryStats().getStats().getCache();
            return usage - (cache != null ? cache : 0);
        }
        return 0;
    } catch (Exception e) {
        log.warn("获取内存统计失败: {}", e.getMessage());
        return 0;
    }
}
```

---

## 7. 下一步

下一篇文档将详细介绍 **多语言支持配置**，包括：
- 语言策略模式设计
- 各语言的编译和执行命令
- Docker 镜像配置
- 语言特定的安全限制

详见 [04-LANGUAGE-SUPPORT.md](04-LANGUAGE-SUPPORT.md)
