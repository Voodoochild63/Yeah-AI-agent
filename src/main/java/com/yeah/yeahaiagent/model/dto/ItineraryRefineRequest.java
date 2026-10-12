package com.yeah.yeahaiagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 多轮局部修改请求。
 */
@Data
@Schema(description = "Conversational itinerary refine request")
public class ItineraryRefineRequest {

    @Schema(description = "Natural-language patch instruction", example = "把晚饭换成附近的日料或烤肉",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Schema(description = "Output language for reason copy. zh / en", example = "zh", defaultValue = "zh")
    private String lang = "zh";
}
