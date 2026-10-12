package com.yeah.yeahaiagent.resilience;

import com.yeah.yeahaiagent.config.UpstreamProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * 本地令牌桶：把 LLM / Serper 的突发流量平滑到安全阈值，超时则友好降级。
 */
@Component
@Slf4j
public class UpstreamApiGuard {

    private final Duration acquireTimeout;
    private final TokenBucketRateLimiter llmLimiter;
    private final TokenBucketRateLimiter serperLimiter;

    public UpstreamApiGuard(UpstreamProperties properties) {
        UpstreamProperties props = properties == null ? new UpstreamProperties() : properties;
        this.acquireTimeout = props.getAcquireTimeout() == null
                ? Duration.ofSeconds(3)
                : props.getAcquireTimeout();
        double permits = props.getPermitsPerSecond() <= 0 ? 5.0d : props.getPermitsPerSecond();
        this.llmLimiter = new TokenBucketRateLimiter(permits);
        this.serperLimiter = new TokenBucketRateLimiter(permits);
    }

    public void acquireLlm() {
        acquire(llmLimiter, "大模型");
    }

    public void acquireSerper() {
        acquire(serperLimiter, "地点检索");
    }

    private void acquire(TokenBucketRateLimiter limiter, String name) {
        if (limiter.tryAcquire(acquireTimeout)) {
            return;
        }
        log.warn("upstream {} rate limited after waiting {}", name, acquireTimeout);
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "当前访问过于频繁，请稍后再试。已触发本地限流保护，避免上游接口过载。");
    }
}
