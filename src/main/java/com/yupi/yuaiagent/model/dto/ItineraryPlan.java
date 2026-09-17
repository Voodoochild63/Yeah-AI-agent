package com.yupi.yuaiagent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 完整行程规划结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryPlan {

    private String title;

    private String summary;

    private List<ItineraryNode> schedule;

    /**
     * 备选方案（下雨/拥堵时的备选店）
     */
    private List<String> backupSuggestions;

    /**
     * 落日评估元数据。由服务端在大模型返回后统一覆盖写入；
     * 同时需要支持前端回传（导出 PDF 时据此渲染落日卡片），因此保持可反序列化
     */
    private SunsetEvaluation sunsetEvaluation;

    public ItineraryPlan(String title, String summary, List<ItineraryNode> schedule, List<String> backupSuggestions) {
        this.title = title;
        this.summary = summary;
        this.schedule = schedule;
        this.backupSuggestions = backupSuggestions;
    }
}
