package com.yeah.yeahaiagent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 主规划一次召回里预置的同类备选店，前端原地轮播，不再打网。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpotCandidateDto {

    private String name;

    private String categoryTag;

    private Double rating;

    private Integer ratingCount;

    private Double latitude;

    private Double longitude;

    private String openingHoursText;

    private String address;

    /** 短亮点短语，前端换点时用来替换综述里的旧动作描写。 */
    private String highlightDesc;
}
