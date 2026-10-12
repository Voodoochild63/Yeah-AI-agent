package com.yeah.yeahaiagent.model.dto;

import com.yeah.yeahaiagent.model.enums.ActionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 局部修改结果：整份行程（仅目标卡片被替换或追加）加上一句说明。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryRefineResponse {

    private ItineraryPlan plan;

    private String reason;

    /** 被替换或新插入节点的下标，前端用来高亮。 */
    private Integer targetIndex;

    private String searchQuery;

    private ActionType action;
}
