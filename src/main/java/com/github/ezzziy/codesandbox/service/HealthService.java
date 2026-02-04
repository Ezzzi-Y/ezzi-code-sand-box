package com.github.ezzziy.codesandbox.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import com.github.ezzziy.codesandbox.executor.ContainerManager;
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
 * 提供系统健康状态、Docker 状态、缓存状态等信息
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthService {

    private final DockerClient dockerClient;
    private final ContainerManager containerManager;
    private final CacheService cacheService;

    private final Instant startTime = Instant.now();

    /**
     * 获取健康检查信息
     */
    public Map<String, Object> getHealthInfo() {
        Map<String, Object> health = new HashMap<>();
        
        health.put("status", "UP");
        health.put("timestamp", Instant.now().toString());
        health.put("uptime", getUptime());
        
        // JVM 信息
        health.put("jvm", getJvmInfo());
        
        // Docker 信息
        health.put("docker", getDockerInfo());
        
        // 缓存信息
        health.put("cache", getCacheInfo());
        
        // 容器信息
        health.put("containers", getContainerInfo());
        
        return health;
    }

    /**
     * 简单健康检查（快速响应）
     */
    public Map<String, Object> ping() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    /**
     * 获取运行时长
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
     * 获取 JVM 信息
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
     * 获取 Docker 信息
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
     * 获取缓存信息
     */
    private Map<String, Object> getCacheInfo() {
        Map<String, Object> cache = new HashMap<>();
        cache.put("size", cacheService.getCacheSize());
        cache.put("stats", cacheService.getCacheStats());
        return cache;
    }

    /**
     * 获取容器信息
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
