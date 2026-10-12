package com.yeah.yeahaiagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 配合 {@code spring.threads.virtual.enabled=true}：
 * Tomcat 请求线程、Spring MVC 调度与 {@code @Async} 都跑在 Java 21 虚拟线程上。
 */
@Configuration
@EnableAsync
public class VirtualThreadConfig {
}
