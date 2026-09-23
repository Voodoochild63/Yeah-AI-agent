package com.yeah.yeahaiagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 行程规划请求。lang 默认 zh，强制结构化输出使用单一语言。
 */
@Data
@Schema(description = "Itinerary planning request")
public class ItineraryRequest {

    @Schema(description = "User preference or revision instruction", example = "帮我安排悉尼一日游", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Schema(description = "Conversation id for multi-turn memory", example = "user_1001")
    private String chatId;

    @Schema(description = "Output language. zh = Simplified Chinese only, en = English only",
            example = "zh", defaultValue = "zh", allowableValues = {"en", "zh"})
    private String lang = "zh";

    @Schema(description = "Taste profile and custom POI library from the frontend RAG centre")
    private RagContext ragContext;
}
