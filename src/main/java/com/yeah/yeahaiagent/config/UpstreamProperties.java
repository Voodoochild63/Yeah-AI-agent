package com.yeah.yeahaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 上游 LLM / Serper 的本地令牌桶与规划骨架缓存。
 */
@ConfigurationProperties(prefix = "yeah.upstream")
public class UpstreamProperties {

    /**
     * 每秒最多向外部大模型或检索发起的调用次数。
     */
    private double permitsPerSecond = 5.0d;

    /**
     * 取令牌最长等待时间，超时则优雅降级，避免把上游 429 打穿到用户。
     */
    private Duration acquireTimeout = Duration.ofSeconds(3);

    /**
     * 相同 suburb / theme / timeOfDay 规划骨架的存活时间。
     */
    private Duration skeletonTtl = Duration.ofMinutes(10);

    public double getPermitsPerSecond() {
        return permitsPerSecond;
    }

    public void setPermitsPerSecond(double permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
    }

    public Duration getAcquireTimeout() {
        return acquireTimeout;
    }

    public void setAcquireTimeout(Duration acquireTimeout) {
        this.acquireTimeout = acquireTimeout;
    }

    public Duration getSkeletonTtl() {
        return skeletonTtl;
    }

    public void setSkeletonTtl(Duration skeletonTtl) {
        this.skeletonTtl = skeletonTtl;
    }
}
