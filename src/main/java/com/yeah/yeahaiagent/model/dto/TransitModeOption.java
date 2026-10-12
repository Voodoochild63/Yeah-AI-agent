package com.yeah.yeahaiagent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一种出行方式的预估。前端切换模式时不再回源。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransitModeOption {

    private String durationText;

    private String distanceText;

    private Integer durationMinutes;

    private Integer distanceMeters;
}
