package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.model.dto.SunsetEvaluation;
import com.yupi.yuaiagent.service.WeatherService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/weather")
public class WeatherController {

    @Resource
    private WeatherService weatherService;

    /**
     * 落日决策查询接口
     * 请求示例：GET /api/ai/weather/sunset-check
     */
    @GetMapping("/sunset-check")
    public SunsetEvaluation checkSunset() {
        return weatherService.evaluateTodaySunset();
    }
}
