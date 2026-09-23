package com.yeah.yeahaiagent.model.dto;

/**
 * 落日观赏决策结果
 *
 * @param recommended          是否推荐看日落
 * @param sunsetTime           日落时间，如 "17:48"
 * @param goldenHourWindow     黄金观赏窗口，如 "17:05 - 18:05"
 * @param cloudCover           云量百分比，数据缺失时为 null
 * @param rainProb             降水概率百分比，数据缺失时为 null
 * @param recommendationReason 判定理由
 * @param recommendedSpot      推荐落日观赏地
 * @param photoTip             拍照机位建议
 */
public record SunsetEvaluation(
        boolean recommended,
        String sunsetTime,
        String goldenHourWindow,
        Integer cloudCover,
        Integer rainProb,
        String recommendationReason,
        String recommendedSpot,
        String photoTip
) {
}
