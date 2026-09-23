package com.yeah.yeahaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class WeatherTool {

    public record WeatherInfo(String location, String condition, int temperature, String recommendation) {}

    @Tool(description = "查询指定城市或区域在特定日期的天气情况与出行适宜度")
    public WeatherInfo getWeather(
            @ToolParam(description = "城市或区域名称，如 Sydney, Kingsford, CBD") String location,
            @ToolParam(description = "查询日期或时段，如 今日, 明天, 上午") String date) {
        
        // 模拟返回雨天数据，测试大模型是否触发室内备选方案逻辑
        return new WeatherInfo(
                location,
                "阵雨 (Rainy)",
                18,
                "室外有降雨风险，建议优先选择室内展馆、商场或室内咖啡馆，并准备雨具。"
        );
    }
}