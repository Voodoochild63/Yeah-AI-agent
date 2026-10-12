package com.yeah.yeahaiagent.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Serper Google Maps 地点。只保留行程组装需要的字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RealPlaceDto {

    private String title;

    private String address;

    private Double rating;

    private Integer ratingCount;

    private String category;

    private Double latitude;

    private Double longitude;

    /**
     * Serper / Google Maps 原始营业时间，例如 "Monday: 8:00 AM – 3:00 PM"。
     */
    private List<String> openingHours;

    /** 给前端直接展示的友好营业时间，例如 "今日 07:00–15:00"。 */
    private String openingHoursText;
}
