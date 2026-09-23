package com.yeah.yeahaiagent.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
