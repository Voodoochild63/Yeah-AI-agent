package com.yeah.yeahaiagent.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 前端私域打卡点，随 ragContext 一并提交。
 */
@Data
public class CustomPoiDto {

    private String id;

    private String poiName;

    private String category;

    private List<String> tags;

    private List<String> vibes;

    private List<String> visualStyle;

    private String personalTip;

    private String description;
}
