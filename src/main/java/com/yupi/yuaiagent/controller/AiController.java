package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.agent.YuManus;
import com.yupi.yuaiagent.app.ItineraryApp;
import com.yupi.yuaiagent.model.dto.ItineraryPlan;
import com.yupi.yuaiagent.service.ItineraryExportService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

@RestController
@RequestMapping("/ai/itinerary")
public class AiController {

    @Resource
    private ItineraryApp itineraryApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private ItineraryExportService itineraryExportService;

    /**
     * 1. 结构化规划行程接口（返回标准 JSON 格式的路线与备选方案）
     * 请求示例：GET /ai/itinerary/plan?message=帮我安排悉尼一日游，早上想吃Surry Hills的Brunch，下午去喝咖啡&chatId=user_1001
     */
    @GetMapping("/plan")
    public ItineraryPlan planItinerary(String message, String chatId) {
        return itineraryApp.doPlanItinerary(message, chatId);
    }

    /**
     * 2. 结合天气与距离工具的智能规划/局部调整接口（驱动 Tool Calling）
     * 请求示例：GET /ai/itinerary/tools?message=下午下暴雨了，把第二站换成室内的&chatId=user_1001
     */
    @GetMapping("/tools")
    public String planWithTools(String message, String chatId) {
        return itineraryApp.doChatWithTools(message, chatId);
    }

    /**
     * 3. 流式生成路线说明（SSE）
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> planStream(String message, String chatId) {
        return itineraryApp.doChatByStream(message, chatId);
    }

    /**
     * 4. 按需导出当前行程 PDF（前端传入页面上的行程 JSON，不重新调用大模型）
     */
    @PostMapping("/export-pdf")
    public ResponseEntity<InputStreamResource> exportItineraryPdf(@RequestBody ItineraryPlan plan) throws IOException {
        File pdfFile = itineraryExportService.exportPlanToPdf(plan);
        InputStreamResource resource = new InputStreamResource(new FileInputStream(pdfFile));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"itinerary.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdfFile.length())
                .body(resource);
    }

    /**
     * 流式调用 Manus 智能体
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message) {
        YuManus yuManus = new YuManus(allTools, dashscopeChatModel);
        return yuManus.runStream(message);
    }
}