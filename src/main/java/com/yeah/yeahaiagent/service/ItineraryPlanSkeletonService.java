package com.yeah.yeahaiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.yeah.yeahaiagent.config.CacheConfig;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 相同 suburb / theme / timeOfDay 的规划骨架：Caffeine {@code get(key, mappingFunction)} 单飞加载，
 * 并发请求共用一次昂贵的 LLM + Serper 生成。
 */
@Service
@Slf4j
public class ItineraryPlanSkeletonService {

    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;

    public ItineraryPlanSkeletonService(CacheManager cacheManager, ObjectMapper objectMapper) {
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper;
    }

    public ItineraryPlan getOrCompute(String key, Supplier<ItineraryPlan> loader) {
        if (key == null || key.isBlank() || loader == null) {
            return loader == null ? null : loader.get();
        }
        Cache<Object, Object> nativeCache = nativeCache();
        if (nativeCache == null) {
            return loader.get();
        }
        ItineraryPlan cached = (ItineraryPlan) nativeCache.get(key, k -> {
            log.info("plan skeleton miss, computing. key={}", abbreviate(key));
            ItineraryPlan computed = loader.get();
            if (computed == null) {
                throw new IllegalStateException("itinerary loader returned null");
            }
            computed.setSessionId(null);
            return computed;
        });
        return copyOf(cached);
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> nativeCache() {
        org.springframework.cache.Cache cache = cacheManager.getCache(CacheConfig.PLAN_SKELETON_CACHE);
        if (!(cache instanceof CaffeineCache caffeineCache)) {
            return null;
        }
        return (Cache<Object, Object>) caffeineCache.getNativeCache();
    }

    ItineraryPlan copyOf(ItineraryPlan plan) {
        if (plan == null) {
            return null;
        }
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(plan), ItineraryPlan.class);
        } catch (Exception ex) {
            log.warn("plan skeleton copy failed, returning original", ex);
            return plan;
        }
    }

    private static String abbreviate(String key) {
        return key.length() <= 80 ? key : key.substring(0, 80) + "...";
    }
}
