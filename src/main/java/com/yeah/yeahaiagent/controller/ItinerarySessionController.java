package com.yeah.yeahaiagent.controller;

import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.ItineraryRefineRequest;
import com.yeah.yeahaiagent.model.dto.ItineraryRefineResponse;
import com.yeah.yeahaiagent.service.ItineraryRefineService;
import com.yeah.yeahaiagent.service.ItinerarySessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 行程多轮会话：REPLACE 一张卡片或 APPEND 新站；返回上一级时销毁。
 */
@RestController
@RequestMapping("/itinerary")
@Tag(name = "Itinerary Session", description = "Conversational itinerary state patching")
public class ItinerarySessionController {

    private final ItinerarySessionService sessionService;
    private final ItineraryRefineService refineService;

    public ItinerarySessionController(ItinerarySessionService sessionService,
                                      ItineraryRefineService refineService) {
        this.sessionService = sessionService;
        this.refineService = refineService;
    }

    @PostMapping("/session")
    @Operation(summary = "Hydrate itinerary session",
            description = "把已有行程写入 sessionCache（历史回放）。首次规划由 /ai/itinerary/plan 自动签发。")
    public ItineraryPlan openSession(@RequestBody ItineraryPlan plan) {
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "行程不能为空");
        }
        return sessionService.begin(plan);
    }

    @PostMapping("/session/{sessionId}/refine")
    @Operation(summary = "Refine itinerary by REPLACE or APPEND",
            description = "解析自然语言意图：REPLACE 只覆盖一张卡片，APPEND 在锚点后插入新站，并重算局部通勤与营业状态。")
    public ItineraryRefineResponse refine(@PathVariable String sessionId,
                                          @RequestBody ItineraryRefineRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return refineService.refine(sessionId, request.getMessage(), request.getLang());
    }

    @DeleteMapping("/session/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Destroy itinerary session", description = "用户返回上一级时清除 Caffeine 会话缓存。")
    public void destroy(@PathVariable String sessionId) {
        sessionService.evict(sessionId);
    }
}
