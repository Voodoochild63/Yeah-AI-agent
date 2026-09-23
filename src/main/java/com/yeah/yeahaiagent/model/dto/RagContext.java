package com.yeah.yeahaiagent.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划请求里的 RAG 上下文：审美画像 + 私域打卡点。
 */
@Data
public class RagContext {

    private TasteProfile tasteProfile;

    private List<CustomPoiDto> customPois = new ArrayList<>();
}
