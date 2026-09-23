package com.yeah.yeahaiagent.model.dto;

/**
 * 停车辅助结果。行程卡片不再展示具体车库名，只用检索词打开 Google Maps。
 *
 * @param hasParkingRecommendation 是否在此节点展示停车入口
 * @param parkingName              旧字段，行程规划不再填写，避免幻觉车库名
 * @param address                  旧字段，行程规划不再填写
 * @param rating                   旧字段，行程规划不再填写
 * @param walkingEstimate          旧字段，行程规划不再填写
 * @param tip                      停车常识，如路边限时或园区访客车位
 * @param parkingSearchKeyword     Google Maps 检索词，如 "parking near The Grounds of Alexandria"
 */
public record ParkingInfo(
        boolean hasParkingRecommendation,
        String parkingName,
        String address,
        Double rating,
        String walkingEstimate,
        String tip,
        String parkingSearchKeyword
) {
    public static ParkingInfo none(String tip) {
        return new ParkingInfo(false, null, null, null, null, tip, null);
    }

    public static ParkingInfo mapsLink(String parkingSearchKeyword, String tip) {
        return new ParkingInfo(true, null, null, null, null, tip, parkingSearchKeyword);
    }
}
