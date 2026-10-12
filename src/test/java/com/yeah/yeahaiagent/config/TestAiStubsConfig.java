package com.yeah.yeahaiagent.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * test profile：MCP 客户端关闭后补一个空的工具提供者，让 ItineraryApp 能完成注入。
 */
@Configuration
@Profile("test")
public class TestAiStubsConfig {

    @Bean
    public ToolCallbackProvider toolCallbackProvider() {
        return ToolCallbackProvider.from();
    }
}
