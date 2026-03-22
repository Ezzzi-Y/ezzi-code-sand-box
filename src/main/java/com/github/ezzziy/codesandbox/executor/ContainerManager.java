package com.github.ezzziy.codesandbox.executor;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.ezzziy.codesandbox.config.ExecutionConfig;
import com.github.ezzziy.codesandbox.strategy.LanguageStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Docker 容器管理器
 * <p>
 * 负责容器的创建、启动、停止、删除等生命周期管理
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerManager {

    private final DockerClient dockerClient;
    private final ExecutionConfig executionConfig;

    /**
     * 当前活跃容器计数
     */
    private final AtomicInteger activeContainers = new AtomicInteger(0);

    /**
     * 容器ID到请求ID的映射
     */
    private final ConcurrentHashMap<String, String> containerRequestMap = new ConcurrentHashMap<>();

    /**
     * 创建执行容器
     *
     * @param strategy  语言策略
     * @param requestId 请求ID
     * @param workDir   工作目录（传 null 则不挂载，用于容器池模式）
     * @return 容器ID
     */
    public String createContainer(LanguageStrategy strategy, String requestId, String workDir) {
        // 检查并发限制
        if (activeContainers.get() >= executionConfig.getMaxConcurrentContainers()) {
            throw new RuntimeException("容器并发数已达上限: " + executionConfig.getMaxConcurrentContainers());
        }

        String containerName = generateContainerName(requestId);
        String image = strategy.getDockerImage();

        // 确保镜像存在
        ensureImageExists(image);

        // 构建 HostConfig（容器池模式不挂载目录）
        HostConfig hostConfig = buildHostConfig(workDir);

        // 构建环境变量
        List<String> envList = new ArrayList<>();
        envList.add("LANG=C.UTF-8");
        envList.add("LC_ALL=C.UTF-8");
        for (String env : strategy.getEnvironmentVariables()) {
            envList.add(env);
        }

        // 创建容器
        // 沙箱专用镜像已内置 sandbox 用户和 /sandbox/workspace 工作目录
        // 无需显式指定 user，由镜像 Dockerfile 中的 USER 指令决定
        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)  // 禁用网络
                .withWorkingDir("/sandbox/workspace")  // 统一工作目录
                .withEnv(envList)
                .withCmd("tail", "-f", "/dev/null")  // 保持容器运行（Alpine busybox 的 sleep 不支持 infinity）
                // 不再显式指定 user，由镜像决定（sandbox-* 镜像默认 USER sandbox）
                .withStdinOpen(true)  // 显式开启 Stdin
                .withStdInOnce(false)  // 允许多次输入
                .withTty(false)  // 禁用 Tty 模式
                .exec();

        String containerId = container.getId();
        containerRequestMap.put(containerId, requestId);
        activeContainers.incrementAndGet();

        log.info("创建容器成功: containerId={}, requestId={}, image={}", 
                containerId.substring(0, 12), requestId, image);

        return containerId;
    }

    /**
     * 启动容器
     */
    public void startContainer(String containerId) {
        dockerClient.startContainerCmd(containerId).exec();
        log.debug("启动容器: {}", containerId.substring(0, 12));
    }

    /**
     * 停止容器
     */
    public void stopContainer(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                dockerClient.stopContainerCmd(containerId)
                        .withTimeout(5)
                        .exec();
                log.debug("停止容器: {}", containerId.substring(0, 12));
            }
        } catch (Exception e) {
            log.warn("停止容器失败: {}, error={}", containerId.substring(0, 12), e.getMessage());
        }
    }

    /**
     * 删除容器
     */
    public void removeContainer(String containerId) {
        try {
            // 先停止
            stopContainer(containerId);
            
            // 强制删除
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();

            containerRequestMap.remove(containerId);
            activeContainers.decrementAndGet();

            log.info("删除容器: {}", containerId.substring(0, 12));
        } catch (Exception e) {
            log.error("删除容器失败: {}, error={}", containerId.substring(0, 12), e.getMessage());
        }
    }

    /**
     * 获取任务工作目录路径（容器池模式）
     * <p>
     * 沙箱镜像已预创建 /sandbox/workspace 目录并归属 sandbox 用户，
     * 直接返回任务子目录路径，无需运行时初始化
     * <p>
     * 目录结构：
     * - /sandbox/workspace/{jobId}/  : 任务工作目录
     *
     * @param jobId 任务 ID
     * @return 任务工作目录路径（容器内路径）
     */
    public String getTaskDirectory(String jobId) {
        return "/sandbox/workspace/" + jobId;
    }

    /**
     * 清理任务工作目录（任务完成后调用）
     * <p>
     * 递归删除任务子目录及其所有内容，作为资源清理机制。
     * 容器必须处于 running 状态才能执行清理操作。
     *
     * @param containerId 容器 ID
     * @param taskDir     任务子目录路径
     */
    public void cleanupTaskDirectory(String containerId, String taskDir) {
        try {
            // 安全检查：确保只清理 /sandbox/workspace/ 下的任务目录
            if (!taskDir.startsWith("/sandbox/workspace/")) {
                log.error("非法的任务目录路径，拒绝清理: taskDir={}", taskDir);
                return;
            }
            
            // 检查容器是否在运行状态
            if (!isContainerRunning(containerId)) {
                log.warn("容器未运行，跳过任务目录清理: containerId={}, taskDir={}", 
                        containerId.substring(0, 12), taskDir);
                return;
            }
            
            // 递归删除任务子目录（以 sandbox 用户执行，匹配文件所有权）
            String execId = dockerClient.execCreateCmd(containerId)
                    .withUser("sandbox")
                    .withCmd("rm", "-rf", taskDir)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec()
                    .getId();
            
            dockerClient.execStartCmd(execId)
                    .exec(new com.github.dockerjava.core.command.ExecStartResultCallback())
                    .awaitCompletion(10, java.util.concurrent.TimeUnit.SECONDS);
            
            log.debug("清理任务目录完成: containerId={}, taskDir={}", containerId.substring(0, 12), taskDir);
        } catch (Exception e) {
            log.warn("清理任务目录失败: containerId={}, taskDir={}, error={}", 
                    containerId.substring(0, 12), taskDir, e.getMessage());
        }
    }

    /**
     * 强制终止容器
     */
    public void killContainer(String containerId) {
        try {
            dockerClient.killContainerCmd(containerId).exec();
            log.warn("强制终止容器: {}", containerId.substring(0, 12));
        } catch (Exception e) {
            log.warn("强制终止容器失败: {}", e.getMessage());
        }
    }

    /**
     * 构建 HostConfig
     * @param workDir 工作目录，传 null 则不挂载（容器池模式）
     */
    private HostConfig buildHostConfig(String workDir) {
        long memoryBytes = executionConfig.getMemoryLimit() * 1024L * 1024L;
        long cpuPeriod = 100000L;
        long cpuQuota = (long) (cpuPeriod * executionConfig.getCpuLimit());

        HostConfig hostConfig = HostConfig.newHostConfig()
                // 内存限制
                .withMemory(memoryBytes)
                .withMemorySwap(memoryBytes)  // 禁用 swap
                .withOomKillDisable(false)
                // CPU 限制
                .withCpuPeriod(cpuPeriod)
                .withCpuQuota(cpuQuota)
                // 进程数限制
                .withPidsLimit((long) executionConfig.getMaxProcesses())
                // 只读根文件系统
                .withReadonlyRootfs(true)
                // 删除所有 Linux capabilities
                .withCapDrop(Capability.ALL)
                // 安全选项
                .withSecurityOpts(List.of(
                        "no-new-privileges:true"
                ))
                // Ulimit 限制
                .withUlimits(List.of(
                        new Ulimit("nofile", executionConfig.getMaxOpenFiles(), executionConfig.getMaxOpenFiles()),
                        new Ulimit("nproc", executionConfig.getMaxProcesses(), executionConfig.getMaxProcesses()),
                        new Ulimit("fsize", executionConfig.getOutputLimit(), executionConfig.getOutputLimit())
                ))
                // 临时文件系统（只读根文件系统下，仅开放必要写目录）
                .withTmpFs(java.util.Map.of(
                        "/tmp", "rw,noexec,nosuid,size=64m",
                        "/sandbox/workspace", "rw,exec,nosuid,size=64m"
                ));
        
        // 仅传统模式挂载工作目录，不再挂载 inputs（统一用 stdin 输入）
        if (workDir != null) {
            hostConfig.withBinds(
                    new Bind(workDir, new Volume("/sandbox/workspace"))
            );
        }
        // 容器池模式：不挂载任何目录
        
        return hostConfig;
    }

    /**
     * 确保镜像存在
     */
    private void ensureImageExists(String image) {
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (Exception e) {
            log.info("镜像不存在，开始拉取: {}", image);
            try {
                dockerClient.pullImageCmd(image)
                        .start()
                        .awaitCompletion();
                log.info("镜像拉取完成: {}", image);
            } catch (Exception ex) {
                throw new RuntimeException("拉取镜像失败: " + image, ex);
            }
        }
    }

    /**
     * 生成容器名称
     */
    private String generateContainerName(String requestId) {
        return String.format("sandbox-%s-%s", requestId, UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * 获取当前活跃容器数
     */
    public int getActiveContainerCount() {
        return activeContainers.get();
    }

    /**
     * 检查容器是否正在运行
     * 同时记录容器状态信息用于诊断
     */
    public boolean isContainerRunning(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            InspectContainerResponse.ContainerState state = inspect.getState();
            
            boolean running = Boolean.TRUE.equals(state.getRunning());
            
            // 记录详细状态用于诊断
            if (!running) {
                log.warn("容器状态异常 - containerId={}, status={}, running={}, exitCode={}, error={}, oomKilled={}",
                        containerId.substring(0, 12),
                        state.getStatus(),
                        state.getRunning(),
                        state.getExitCodeLong(),
                        state.getError(),
                        state.getOOMKilled());
            }
            
            return running;
        } catch (Exception e) {
            log.error("检查容器状态失败: containerId={}, error={}", containerId.substring(0, 12), e.getMessage());
            return false;
        }
    }

    /**
     * 清理容器工作目录（用于容器复用前的全量清理）
     * <p>
     * 清理 /sandbox/workspace/ 下的所有内容。
     * 容器必须处于 running 状态才能执行清理操作。
     */
    public void cleanContainer(String containerId) {
        try {
            // 检查容器是否在运行状态
            if (!isContainerRunning(containerId)) {
                log.warn("容器未运行，跳过工作目录清理: containerId={}", containerId.substring(0, 12));
                return;
            }
            
            // 清理工作目录下的所有内容（保留目录本身，以 sandbox 用户执行）
            String execId = dockerClient.execCreateCmd(containerId)
                    .withUser("sandbox")
                    .withCmd("sh", "-c", "rm -rf /sandbox/workspace/* 2>/dev/null || true")
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec()
                    .getId();
            
            dockerClient.execStartCmd(execId)
                    .exec(new com.github.dockerjava.core.command.ExecStartResultCallback())
                    .awaitCompletion(10, java.util.concurrent.TimeUnit.SECONDS);
            
            log.debug("清理容器工作目录完成: containerId={}", containerId.substring(0, 12));
        } catch (Exception e) {
            log.warn("清理容器工作目录失败: containerId={}, error={}", containerId.substring(0, 12), e.getMessage());
            throw new RuntimeException("清理容器失败", e);
        }
    }
}
