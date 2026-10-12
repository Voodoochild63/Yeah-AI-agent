package com.yeah.yeahaiagent.model.dto;

import com.yeah.yeahaiagent.model.enums.LanguageEnum;
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

    /** 中文标题，例如 "Alexandria 工业复古半日游"。 */
    private String titleZh;

    /** 英文标题，例如 "Alexandria Industrial Vintage Half-Day Tour"。 */
    private String titleEn;

    /**
     * 一句话简述，约 25 字。
     */
    private String summary;

    private String summaryZh;

    private String summaryEn;

    /**
     * 整体规划思路与体验亮点，约 60-120 字。语言跟随请求的 lang。
     */
    private String detailedDescription;

    private String detailedDescriptionZh;

    private String detailedDescriptionEn;

    private List<ItineraryNode> schedule;

    /**
     * 备选方案（下雨/拥堵时的备选店）
     */
    private List<String> backupSuggestions;

    private List<String> backupSuggestionsZh;

    private List<String> backupSuggestionsEn;

    /**
     * 落日评估元数据。由服务端在大模型返回后统一覆盖写入；
     * 同时需要支持前端回传（导出 PDF 时据此渲染落日卡片），因此保持可反序列化
     */
    private SunsetEvaluation sunsetEvaluation;

    /**
     * 多轮局部修改会话 ID。首次规划写入 Caffeine sessionCache 后返回给前端；
     * 模型不必填写，服务端会覆盖。
     */
    private String sessionId;

    public ItineraryPlan(String title, String summary, List<ItineraryNode> schedule, List<String> backupSuggestions) {
        this.title = title;
        this.summary = summary;
        this.schedule = schedule;
        this.backupSuggestions = backupSuggestions;
    }

    public String localizedTitle(LanguageEnum language) {
        return pick(language, titleZh, titleEn, title);
    }

    public String localizedSummary(LanguageEnum language) {
        return pick(language, summaryZh, summaryEn, summary);
    }

    public String localizedDetailedDescription(LanguageEnum language) {
        return pick(language, detailedDescriptionZh, detailedDescriptionEn, detailedDescription);
    }

    public List<String> localizedBackupSuggestions(LanguageEnum language) {
        List<String> preferred = language != null && !language.isZh() ? backupSuggestionsEn : backupSuggestionsZh;
        if (preferred != null && !preferred.isEmpty()) {
            return preferred;
        }
        if (backupSuggestions != null && !backupSuggestions.isEmpty()) {
            return backupSuggestions;
        }
        List<String> other = language != null && !language.isZh() ? backupSuggestionsZh : backupSuggestionsEn;
        return other == null ? List.of() : other;
    }

    static String pick(LanguageEnum language, String zh, String en, String fallback) {
        boolean english = language != null && !language.isZh();
        String primary = english ? en : zh;
        String secondary = english ? zh : en;
        if (notBlank(primary)) {
            return primary.trim();
        }
        if (notBlank(fallback)) {
            return fallback.trim();
        }
        return notBlank(secondary) ? secondary.trim() : "";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
