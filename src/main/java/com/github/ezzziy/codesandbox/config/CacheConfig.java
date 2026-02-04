package com.github.ezzziy.codesandbox.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 缓存配置
 * <p>
 * 用于缓存输入数据集，避免重复下载和解压
 * </p>
 *
 * @author ezzziy
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String INPUT_DATA_CACHE = "inputDataCache";

    @Value("${sandbox.cache.max-size:500}")
    private int maxSize;

    @Value("${sandbox.cache.expire-minutes:60}")
    private int expireMinutes;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(INPUT_DATA_CACHE);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(expireMinutes, TimeUnit.MINUTES)
                .recordStats());
        return cacheManager;
    }
}
