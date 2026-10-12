package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.config.CacheConfig;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * 行程多轮会话：Caffeine sessionCache，30 分钟无访问过期。
 */
@Service
public class ItinerarySessionService {

    private final CacheManager cacheManager;

    public ItinerarySessionService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * 首次规划或历史回放时签发 sessionId，并把当前行程写入缓存。
     */
    public ItineraryPlan begin(ItineraryPlan plan) {
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "行程不能为空");
        }
        String sessionId = UUID.randomUUID().toString();
        plan.setSessionId(sessionId);
        sessionCache().put(sessionId, plan);
        return plan;
    }

    public ItineraryPlan get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionCache().get(sessionId.trim(), ItineraryPlan.class);
    }

    public ItineraryPlan require(String sessionId) {
        ItineraryPlan plan = get(sessionId);
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话已过期或不存在，请返回上一级后重新规划");
        }
        return plan;
    }

    public void put(String sessionId, ItineraryPlan plan) {
        if (sessionId == null || sessionId.isBlank() || plan == null) {
            return;
        }
        plan.setSessionId(sessionId.trim());
        sessionCache().put(sessionId.trim(), plan);
    }

    public void evict(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionCache().evict(sessionId.trim());
    }

    private Cache sessionCache() {
        Cache cache = cacheManager.getCache(CacheConfig.SESSION_CACHE);
        if (cache == null) {
            throw new IllegalStateException("sessionCache 未注册，请检查 CacheConfig");
        }
        return cache;
    }
}
