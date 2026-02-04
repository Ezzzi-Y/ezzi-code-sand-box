package com.github.ezzziy.codesandbox.controller;

import com.github.ezzziy.codesandbox.common.result.Result;
import com.github.ezzziy.codesandbox.service.HealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查控制器
 * <p>
 * 提供系统健康状态检查的 API 接口
 * </p>
 *
 * @author ezzziy
 */
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    /**
     * 简单健康检查（用于负载均衡器探针）
     */
    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        return Result.success(healthService.ping());
    }

    /**
     * 详细健康检查
     */
    @GetMapping
    public Result<Map<String, Object>> health() {
        return Result.success(healthService.getHealthInfo());
    }

    /**
     * 存活探针
     */
    @GetMapping("/liveness")
    public Result<Map<String, Object>> liveness() {
        return Result.success(Map.of(
                "status", "UP",
                "probe", "liveness"
        ));
    }

    /**
     * 就绪探针
     */
    @GetMapping("/readiness")
    public Result<Map<String, Object>> readiness() {
        // 可以添加更多检查，如 Docker 连接状态
        Map<String, Object> health = healthService.getHealthInfo();
        @SuppressWarnings("unchecked")
        Map<String, Object> docker = (Map<String, Object>) health.get("docker");
        
        if ("UP".equals(docker.get("status"))) {
            return Result.success(Map.of(
                    "status", "UP",
                    "probe", "readiness"
            ));
        } else {
            return Result.error(Map.of(
                    "status", "DOWN",
                    "probe", "readiness",
                    "reason", "Docker not available"
            ));
        }
    }
}
