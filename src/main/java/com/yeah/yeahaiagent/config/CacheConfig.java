package com.yeah.yeahaiagent.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 本地缓存配置。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 气象缓存：同一坐标 + 同一天的预报在 30 分钟内直接命中内存，不再打外部接口。
     */
    public static final String WEATHER_CACHE = "weatherCache";

    @Bean
    public CaffeineCacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.registerCustomCache(WEATHER_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(30))
                .maximumSize(100)
                .build());
        return cacheManager;
    }
}
