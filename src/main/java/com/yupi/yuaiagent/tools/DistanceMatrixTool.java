package com.yupi.yuaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class DistanceMatrixTool {

    public record RouteInfo(String origin, String destination, String mode, int durationMinutes, double distanceKm) {}

    @Tool(description = "计算起点与终点之间的通行距离与预估耗时，用于合理规划时间轴")
    public RouteInfo calculateTravelTime(
            @ToolParam(description = "出发地点名称") String origin,
            @ToolParam(description = "目的地点名称") String destination,
            @ToolParam(description = "出行方式，如 transit(公共交通), walking(步行), driving(驾车)") String mode) {
        
        // 模拟通行耗时计算，为大模型提供时间轴排序依据
        return new RouteInfo(origin, destination, mode, 20, 4.5);
    }
}