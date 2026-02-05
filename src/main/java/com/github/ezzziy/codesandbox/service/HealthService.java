package com.github.ezzziy.codesandbox.service;

import java.util.Map;

/**
 * 健康检查服务接口
 * <p>
 * 提供系统健康状态信息查询，包括：
 * - JVM 内存、运行状态
 * - Docker 容器、镜像状态
 * - 缓存统计信息
 * - 容器池状态
 * </p>
 *
 * @author ezzziy
 */
public interface HealthService {

    /**
     * 获取详细的健康检查信息
     * <p>
     * 包含 JVM、Docker、缓存、容器等完整状态信息，适合定期监控
     * </p>
     *
     * @return 包含多项状态指标的 Map
     */
    Map<String, Object> getHealthInfo();

    /**
     * 简单的健康检查（快速响应）
     * <p>
     * 仅返回基本的运行状态，用于负载均衡器和存活探针
     * </p>
     *
     * @return 包含 status 和 timestamp 的 Map
     */
    Map<String, Object> ping();
}
