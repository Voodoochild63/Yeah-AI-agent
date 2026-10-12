package com.yeah.yeahaiagent.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(UpstreamProperties.class)
public class CacheConfig {

    /**
     * 气象缓存：同一坐标 + 同一天的预报在 30 分钟内直接命中内存，不再打外部接口。
     */
    public static final String WEATHER_CACHE = "weatherCache";

    /**
     * 行程会话缓存：Key 为 sessionId，Value 为当前 ItineraryPlan。
     * 30 分钟无访问即过期，用户点「返回上一级」时主动清除。
     */
    public static final String SESSION_CACHE = "sessionCache";

    /**
     * 规划骨架缓存：相同 suburb / theme / timeOfDay 共用一次生成，Caffeine 按 key 单飞加载。
     */
    public static final String PLAN_SKELETON_CACHE = "planSkeletonCache";

    public CaffeineCacheManager cacheManager() {
        return cacheManager(new UpstreamProperties());
    }

    @Bean
    public CaffeineCacheManager cacheManager(UpstreamProperties upstreamProperties) {
        Duration skeletonTtl = upstreamProperties == null || upstreamProperties.getSkeletonTtl() == null
                ? Duration.ofMinutes(10)
                : upstreamProperties.getSkeletonTtl();
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.registerCustomCache(WEATHER_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(30))
                .maximumSize(100)
                .build());
        cacheManager.registerCustomCache(SESSION_CACHE, Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(30))
                .maximumSize(200)
                .build());
        cacheManager.registerCustomCache(PLAN_SKELETON_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(skeletonTtl)
                .maximumSize(200)
                .build());
        return cacheManager;
    }
}
