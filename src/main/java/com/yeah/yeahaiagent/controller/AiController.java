package com.yeah.yeahaiagent.controller;

import com.yeah.yeahaiagent.agent.YuManus;
import com.yeah.yeahaiagent.app.ItineraryApp;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.ItineraryRequest;
import com.yeah.yeahaiagent.service.ItineraryExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/ai/itinerary")
@Tag(name = "Itinerary", description = "Structured itinerary planning")
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
     * 请求示例：GET /ai/itinerary/plan?message=帮我安排悉尼一日游&chatId=user_1001&lang=zh
     */
    @GetMapping("/plan")
    @Operation(summary = "Plan itinerary (query)", description = "兼容旧 GET。推荐使用 POST /plan 提交 ragContext。")
    public ItineraryPlan planItinerary(
            @RequestParam String message,
            @RequestParam(required = false) String chatId,
            @RequestParam(required = false, defaultValue = "zh") String lang) {
        return itineraryApp.doPlanItinerary(message, chatId, lang, null);
    }

    @PostMapping("/plan")
    @Operation(summary = "Plan itinerary", description = "Retrieve-Read-Filter-Reason：Serper 真实高分店 + RAG 审美后生成行程")
    public ItineraryPlan planItineraryPost(@RequestBody ItineraryRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return itineraryApp.doPlanItinerary(
                request.getMessage(),
                request.getChatId(),
                request.getLang(),
                request.getRagContext());
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
    @Operation(summary = "Export itinerary PDF", description = "lang=zh 中文排版；lang=en 英文排版。默认 zh")
    public ResponseEntity<InputStreamResource> exportItineraryPdf(
            @RequestBody ItineraryPlan plan,
            @RequestParam(required = false, defaultValue = "zh") String lang) throws IOException {
        File pdfFile = itineraryExportService.exportPlanToPdf(plan, lang);
        InputStreamResource resource = new InputStreamResource(new FileInputStream(pdfFile));
        boolean chinese = lang == null || !lang.toLowerCase().startsWith("en");
        String asciiName = chinese ? "itinerary-zh.pdf" : "itinerary-en.pdf";
        String displayName = chinese ? "悉尼行程单.pdf" : "sydney-itinerary.pdf";
        String encodedName = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName)
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