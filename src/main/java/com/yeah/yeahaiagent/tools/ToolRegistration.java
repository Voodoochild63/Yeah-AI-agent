package com.yeah.yeahaiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 集中的工具注册类
 */
@Configuration
public class ToolRegistration {

    @Bean
    public ToolCallback[] allTools() {
        WeatherTool weatherTool = new WeatherTool();
        DistanceMatrixTool distanceMatrixTool = new DistanceMatrixTool();
        TerminateTool terminateTool = new TerminateTool();

        return ToolCallbacks.from(
                weatherTool,
                distanceMatrixTool,
                terminateTool
        );
    }
}