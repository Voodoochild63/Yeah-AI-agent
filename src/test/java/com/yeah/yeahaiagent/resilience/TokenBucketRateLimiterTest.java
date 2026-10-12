package com.yeah.yeahaiagent.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("上游令牌桶限流")
class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("桶内有令牌时立即通过")
    void acquiresWhenTokensAvailable() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5);
        assertThat(limiter.tryAcquire(Duration.ofMillis(50))).isTrue();
        assertThat(limiter.tryAcquire(Duration.ofMillis(50))).isTrue();
    }

    @Test
    @DisplayName("令牌耗尽且等待超时则拒绝")
    void timesOutWhenEmpty() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(0.2d);
        assertThat(limiter.tryAcquire(Duration.ZERO)).isTrue();
        assertThat(limiter.tryAcquire(Duration.ofMillis(30))).isFalse();
    }

    @Test
    @DisplayName("Guard 超时抛出友好 503，而不是上游 429")
    void guardThrowsFriendlyUnavailable() {
        UpstreamPropertiesSlow guard = new UpstreamPropertiesSlow();
        assertThatThrownBy(() -> {
            guard.acquireLlm();
            guard.acquireLlm();
        }).isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static final class UpstreamPropertiesSlow extends UpstreamApiGuard {
        UpstreamPropertiesSlow() {
            super(slowProps());
        }

        private static com.yeah.yeahaiagent.config.UpstreamProperties slowProps() {
            var props = new com.yeah.yeahaiagent.config.UpstreamProperties();
            props.setPermitsPerSecond(0.2d);
            props.setAcquireTimeout(Duration.ofMillis(20));
            return props;
        }
    }
}
