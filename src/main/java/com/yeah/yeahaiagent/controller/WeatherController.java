package com.yeah.yeahaiagent.controller;

import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import com.yeah.yeahaiagent.service.WeatherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/weather")
@Tag(name = "Weather", description = "Sunset evaluation")
public class WeatherController {

    @Resource
    private WeatherService weatherService;

    /**
     * 落日决策查询接口
     * 请求示例：GET /api/ai/weather/sunset-check?lang=en
     */
    @GetMapping("/sunset-check")
    @Operation(summary = "Sunset check", description = "lang=en or lang=zh. Default en.")
    public SunsetEvaluation checkSunset(
            @Parameter(description = "Output language", example = "en")
            @RequestParam(defaultValue = "en")
            @Schema(allowableValues = {"en", "zh"}, defaultValue = "en")
            String lang) {
        return weatherService.evaluateTodaySunset(lang);
    }
}
