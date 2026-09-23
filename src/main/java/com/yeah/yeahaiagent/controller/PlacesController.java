package com.yeah.yeahaiagent.controller;

import com.yeah.yeahaiagent.model.dto.ParkingInfo;
import com.yeah.yeahaiagent.service.ParkingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地点增强相关查询（停车等），独立于行程规划主接口，便于 Knife4j 验证。
 */
@RestController
@RequestMapping("/ai/places")
@Tag(name = "Places", description = "Nearby services such as parking")
public class PlacesController {

    @Resource
    private ParkingService parkingService;

    /**
     * 停车辅助查询
     * 请求示例：GET /api/ai/places/parking-check?lat=-33.8800&lng=151.2050&poiName=SurryHills&lang=en
     */
    @GetMapping("/parking-check")
    @Operation(summary = "Parking check", description = "lang=en or lang=zh. Default en.")
    public ParkingInfo checkParking(
            @RequestParam(defaultValue = "-33.8800") double lat,
            @RequestParam(defaultValue = "151.2050") double lng,
            @RequestParam(required = false) String poiName,
            @Parameter(description = "Output language", example = "en")
            @RequestParam(defaultValue = "en")
            @Schema(allowableValues = {"en", "zh"}, defaultValue = "en")
            String lang) {
        return parkingService.findNearbyParking(lat, lng, poiName, lang);
    }
}
