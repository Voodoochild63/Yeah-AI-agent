package com.yeah.yeahaiagent.model.dto;

import lombok.Data;

/**
 * 用户审美画像，来自前端 Taste Profile。
 */
@Data
public class TasteProfile {

    /** 例如 工业复古、自然光影、扫街摄影 */
    private String aesthetic;

    /** 例如 高对比度倒影、人文纪实 */
    private String photoStyle;

    /** relaxed / moderate / active */
    private String pace;

    /** 专属避坑，例如偏爱独立咖啡馆 */
    private String customNotes;
}
