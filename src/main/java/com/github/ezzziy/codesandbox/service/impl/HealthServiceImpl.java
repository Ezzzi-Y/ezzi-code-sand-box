package com.github.ezzziy.codesandbox.service.impl;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import com.github.ezzziy.codesandbox.executor.ContainerManager;
import com.github.ezzziy.codesandbox.service.HealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查服务
 * <p>
 * 提供系统健康状态、Docker 状态等信息
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthServiceImpl implements HealthService {

    private final DockerClient dockerClient;
    private final ContainerManager containerManager;

    private final Instant startTime = Instant.now();

    /**
     * 获取详细的健康检查信息
     * <p>
     * 收集并返回以下信息：
     * - status: 服务状态（UP/DOWN）
     * - timestamp: 检查时间戳
     * - uptime: 服务运行时长
     * - jvm: Java 虚拟机内存、线程等信息
     * - docker: Docker 引擎和容器状态
     * - containers: 活跃容器计数
     * </p>
     *
     * @return 包含完整健康状态信息的 Map
     */
    public Map<String, Object> getHealthInfo() {
        Map<String, Object> health = new HashMap<>();
        
        health.put("status", "UP");
        health.put("timestamp", Instant.now().toString());
        health.put("uptime", getUptime());
        
        health.put("jvm", getJvmInfo());
        
        health.put("docker", getDockerInfo());
        
        health.put("containers", getContainerInfo());
        
        return health;
    }

    /**
     * 简单的健康检查（快速响应）
     * <p>
     * 返回基本的运行状态，不进行深层检查，用于：
     * - Kubernetes 存活探针（liveness probe）
     * - 负载均衡器健康检查
     * - 简单的可用性验证
     * </p>
     *
     * @return 包含 status 和 timestamp 的 Map
     */
    public Map<String, Object> ping() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    /**
     * 计算并返回服务运行时长
     * <p>
     * 格式：Xd Yh Zm Ss（天 小时 分钟 秒）
     * </p>
     *
     * @return 格式化后的运行时长字符串
     */
    private String getUptime() {
        Duration uptime = Duration.between(startTime, Instant.now());
        long days = uptime.toDays();
        long hours = uptime.toHoursPart();
        long minutes = uptime.toMinutesPart();
        long seconds = uptime.toSecondsPart();
        return String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);
    }

    /**
     * 收集 JVM 运行时信息
     * <p>
     * 包括：
     * - JVM 版本和厂商
     * - 堆内存使用情况（已用/最大/使用率）
     * - 非堆内存使用
     * - 可用处理器核心数
     * </p>
     *
     * @return 包含 JVM 信息的 Map
     */
    private Map<String, Object> getJvmInfo() {
        Map<String, Object> jvm = new HashMap<>();
        
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        
        jvm.put("version", runtime.getVmVersion());
        jvm.put("vendor", runtime.getVmVendor());
        
        var heapUsage = memory.getHeapMemoryUsage();
        jvm.put("heapUsed", formatBytes(heapUsage.getUsed()));
        jvm.put("heapMax", formatBytes(heapUsage.getMax()));
        jvm.put("heapUsage", String.format("%.1f%%", 
                (double) heapUsage.getUsed() / heapUsage.getMax() * 100));
        
        var nonHeapUsage = memory.getNonHeapMemoryUsage();
        jvm.put("nonHeapUsed", formatBytes(nonHeapUsage.getUsed()));
        
        jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        
        return jvm;
    }

    /**
     * 查询 Docker 引擎状态信息
     * <p>
     * 尝试连接 Docker 并获取：
     * - 版本信息
     * - 容器计数（总计、运行、暂停、已停止）
     * - 镜像总数
     * - 存储驱动和内存信息
     * 
     * 如果 Docker 连接失败，返回 DOWN 状态和错误信息
     * </p>
     *
     * @return 包含 Docker 状态的 Map
     */
    private Map<String, Object> getDockerInfo() {
        Map<String, Object> docker = new HashMap<>();
        
        try {
            Info info = dockerClient.infoCmd().exec();
            docker.put("status", "UP");
            docker.put("serverVersion", info.getServerVersion());
            docker.put("containers", info.getContainers());
            docker.put("containersRunning", info.getContainersRunning());
            docker.put("containersPaused", info.getContainersPaused());
            docker.put("containersStopped", info.getContainersStopped());
            docker.put("images", info.getImages());
            docker.put("driver", info.getDriver());
            docker.put("memoryTotal", formatBytes(info.getMemTotal()));
            docker.put("cpus", info.getNCPU());
        } catch (Exception e) {
            docker.put("status", "DOWN");
            docker.put("error", e.getMessage());
            log.error("Docker 健康检查失败", e);
        }
        
        return docker;
    }

    /**
     * 获取容器池中的活跃容器数
     * <p>
     * 返回当前正在运行或可用的容器计数
     * </p>
     *
     * @return 包含容器计数的 Map
     */
    private Map<String, Object> getContainerInfo() {
        Map<String, Object> containers = new HashMap<>();
        containers.put("active", containerManager.getActiveContainerCount());
        return containers;
    }

    /**
     * 格式化字节数
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
