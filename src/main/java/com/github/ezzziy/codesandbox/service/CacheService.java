package com.github.ezzziy.codesandbox.service;

import com.github.ezzziy.codesandbox.config.CacheConfig;
import com.github.ezzziy.codesandbox.model.dto.InputDataSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 缓存服务
 * <p>
 * 管理输入数据集的缓存
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final CacheManager cacheManager;

    /**
     * 获取缓存的输入数据集
     *
     * @param dataId 数据集ID
     * @return 输入数据集，如果不存在返回空
     */
    public Optional<InputDataSet> getInputData(String dataId) {
        Cache cache = cacheManager.getCache(CacheConfig.INPUT_DATA_CACHE);
        if (cache == null) {
            return Optional.empty();
        }

        InputDataSet data = cache.get(dataId, InputDataSet.class);
        if (data != null) {
            log.debug("缓存命中: dataId={}", dataId);
            return Optional.of(data);
        }

        log.debug("缓存未命中: dataId={}", dataId);
        return Optional.empty();
    }

    /**
     * 缓存输入数据集
     *
     * @param dataId       数据集ID
     * @param inputDataSet 输入数据集
     */
    public void putInputData(String dataId, InputDataSet inputDataSet) {
        Cache cache = cacheManager.getCache(CacheConfig.INPUT_DATA_CACHE);
        if (cache != null) {
            cache.put(dataId, inputDataSet);
            log.debug("缓存输入数据集: dataId={}, size={}", dataId, inputDataSet.size());
        }
    }

    /**
     * 移除缓存的输入数据集
     *
     * @param dataId 数据集ID
     */
    public void evictInputData(String dataId) {
        Cache cache = cacheManager.getCache(CacheConfig.INPUT_DATA_CACHE);
        if (cache != null) {
            cache.evict(dataId);
            log.info("移除缓存: dataId={}", dataId);
        }
    }

    /**
     * 清空所有输入数据缓存
     */
    public void clearAll() {
        Cache cache = cacheManager.getCache(CacheConfig.INPUT_DATA_CACHE);
        if (cache != null) {
            cache.clear();
            log.info("清空所有输入数据缓存");
        }
    }

    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        Cache cache = cacheManager.getCache(CacheConfig.INPUT_DATA_CACHE);
        if (cache instanceof CaffeineCache caffeineCache) {
            var nativeCache = caffeineCache.getNativeCache();
            var stats = nativeCache.stats();
            return String.format(
                    "hitCount=%d, missCount=%d, hitRate=%.2f%%, evictionCount=%d, size=%d",
                    stats.hitCount(),
                    stats.missCount(),
                    stats.hitRate() * 100,
                    stats.evictionCount(),
                    nativeCache.estimatedSize()
            );
        }
        return "N/A";
    }

    /**
     * 获取缓存大小
     */
    public long getCacheSize() {
        Cache cache = cacheManager.getCache(CacheConfig.INPUT_DATA_CACHE);
        if (cache instanceof CaffeineCache caffeineCache) {
            return caffeineCache.getNativeCache().estimatedSize();
        }
        return 0;
    }
}
