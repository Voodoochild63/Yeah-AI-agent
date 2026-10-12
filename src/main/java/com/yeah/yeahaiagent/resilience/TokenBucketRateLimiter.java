package com.yeah.yeahaiagent.resilience;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * 内存令牌桶：平滑等待取令牌，超时返回 false，不把上游 429 直接打到调用方。
 * 使用 {@link LockSupport} 等待，避免 {@code Object.wait} 钉死虚拟线程载体。
 */
public final class TokenBucketRateLimiter {

    private final double permitsPerSecond;
    private final double capacity;
    private double storedPermits;
    private long lastRefillNanos;

    public TokenBucketRateLimiter(double permitsPerSecond) {
        this.permitsPerSecond = Math.max(permitsPerSecond, 0.1d);
        this.capacity = Math.max(1.0d, this.permitsPerSecond);
        this.storedPermits = this.capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public boolean tryAcquire(Duration timeout) {
        long timeoutNanos = timeout == null ? 0L : Math.max(0L, timeout.toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        while (true) {
            long waitNanos;
            synchronized (this) {
                refill(System.nanoTime());
                if (storedPermits >= 1.0d) {
                    storedPermits -= 1.0d;
                    return true;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                waitNanos = (long) Math.ceil((1.0d - storedPermits) / permitsPerSecond * 1_000_000_000L);
                waitNanos = Math.min(remaining, Math.max(waitNanos, 1_000_000L));
            }
            LockSupport.parkNanos(waitNanos);
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed <= 0L) {
            return;
        }
        storedPermits = Math.min(capacity, storedPermits + elapsed * permitsPerSecond / 1_000_000_000.0d);
        lastRefillNanos = nowNanos;
    }
}
