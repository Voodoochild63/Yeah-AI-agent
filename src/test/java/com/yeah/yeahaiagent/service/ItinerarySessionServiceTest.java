package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.config.CacheConfig;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("行程会话 Caffeine sessionCache")
class ItinerarySessionServiceTest {

    private ItinerarySessionService sessions;
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new CacheConfig().cacheManager();
        sessions = new ItinerarySessionService(cacheManager);
    }

    @Test
    @DisplayName("sessionCache 按 30 分钟无访问过期注册")
    void expireAfterAccessThirtyMinutes() {
        CaffeineCache cache = (CaffeineCache) cacheManager.getCache(CacheConfig.SESSION_CACHE);
        assertThat(cache).isNotNull();
        assertThat(cache.getNativeCache().policy().expireAfterAccess())
                .isPresent()
                .get()
                .extracting(expiry -> expiry.getExpiresAfter())
                .isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("begin 签发 sessionId 并写回缓存，evict 后 require 抛 404")
    void beginGetAndEvict() {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setTitle("Alexandria half-day");
        ItineraryPlan stored = sessions.begin(plan);

        assertThat(stored.getSessionId()).isNotBlank();
        assertThat(sessions.get(stored.getSessionId()).getTitle()).isEqualTo("Alexandria half-day");

        sessions.evict(stored.getSessionId());
        assertThat(sessions.get(stored.getSessionId())).isNull();
        assertThatThrownBy(() -> sessions.require(stored.getSessionId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("会话已过期");
    }
}
