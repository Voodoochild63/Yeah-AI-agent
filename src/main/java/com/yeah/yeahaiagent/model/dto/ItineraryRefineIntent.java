package com.yeah.yeahaiagent.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yeah.yeahaiagent.model.enums.ActionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 结构化输出：REPLACE 替换一站，APPEND 在某站之后插入新站。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItineraryRefineIntent {

    /** REPLACE 覆盖目标卡片；APPEND 在目标卡片之后追加。缺省视为 APPEND，避免新诉求误覆盖末站。 */
    private ActionType action;

    /**
     * REPLACE：被替换节点下标。
     * APPEND：锚点下标，新站插在它后面；时间线末尾追加时取最后一站。
     */
    private Integer targetIndex;

    /** Google Maps 检索词，可用 {suburb} 占位。 */
    private String searchQuery;

    /** 展示给用户的一句说明。 */
    private String reason;

    /**
     * 目标品类：WALK / SCENIC / PARK / ATTRACTION / BAR / DESSERT / SHOPPING / DINING / CAFE / TEA。
     * 散步、观景绝不能落成餐厅检索。
     */
    private String category;

    public ItineraryRefineIntent(Integer targetIndex, String searchQuery, String reason) {
        this(ActionType.REPLACE, targetIndex, searchQuery, reason, null);
    }

    public ItineraryRefineIntent(ActionType action, Integer targetIndex, String searchQuery, String reason) {
        this(action, targetIndex, searchQuery, reason, null);
    }

    public ActionType resolvedAction() {
        return action == null ? ActionType.APPEND : action;
    }
}
