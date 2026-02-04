package com.github.ezzziy.codesandbox.controller;

import com.github.ezzziy.codesandbox.common.result.Result;
import com.github.ezzziy.codesandbox.service.CacheService;
import com.github.ezzziy.codesandbox.service.InputDataService;
import com.github.ezzziy.codesandbox.util.OssUrlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 缓存管理控制器
 * <p>
 * 提供缓存状态查看和按 ObjectKey 删除缓存的功能
 * 用于保证数据一致性：当后端更新输入数据后，通知沙箱清除对应缓存
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@RestController
@RequestMapping("/cache")
@RequiredArgsConstructor
public class CacheController {

    private final CacheService cacheService;
    private final InputDataService inputDataService;

    /**
     * 获取缓存统计信息
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("stats", cacheService.getCacheStats());
        stats.put("size", cacheService.getCacheSize());
        return Result.success(stats);
    }

    /**
     * 检查指定 ObjectKey 是否已缓存
     *
     * @param objectKey 对象键（格式：bucket/path/to/object.zip）
     */
    @GetMapping("/check")
    public Result<Map<String, Object>> checkCache(@RequestParam String objectKey) {
        boolean cached = inputDataService.isCached(objectKey);

        Map<String, Object> result = new HashMap<>();
        result.put("objectKey", objectKey);
        result.put("cached", cached);
        return Result.success(result);
    }

    /**
     * 根据 ObjectKey 删除缓存
     * <p>
     * 当后端更新了输入数据（如更新了题目的测试用例）后，
     * 调用此接口清除对应的缓存，保证数据一致性
     * </p>
     *
     * @param objectKey 对象键（格式：bucket/path/to/object.zip）
     */
    @DeleteMapping("/evict")
    public Result<Map<String, Object>> evictByObjectKey(@RequestParam String objectKey) {
        log.info("删除缓存请求: objectKey={}", objectKey);
        inputDataService.evictByObjectKey(objectKey);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "缓存已删除");
        result.put("objectKey", objectKey);
        return Result.success(result);
    }

    /**
     * 根据预签名 URL 删除缓存（自动提取 ObjectKey）
     * <p>
     * 便捷接口：直接传入预签名 URL，系统自动提取 ObjectKey 并删除缓存
     * </p>
     *
     * @param url 预签名 URL
     */
    @DeleteMapping("/evict-by-url")
    public Result<Map<String, Object>> evictByUrl(@RequestParam String url) {
        String objectKey = OssUrlParser.extractObjectKey(url);
        log.info("根据 URL 删除缓存: objectKey={}", objectKey);
        inputDataService.evictByObjectKey(objectKey);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "缓存已删除");
        result.put("objectKey", objectKey);
        return Result.success(result);
    }

    /**
     * 清空所有缓存
     */
    @DeleteMapping("/all")
    public Result<Map<String, Object>> clearAllCache() {
        log.info("清空所有输入数据缓存");
        cacheService.clearAll();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "所有缓存已清空");
        return Result.success(result);
    }
}
