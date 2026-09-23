package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ParkingInfo;

/**
 * 智能停车检索。默认走本地零成本适配器，可切换 Google Places。
 */
public interface ParkingService {

    /**
     * 查找距离目标点最近、匹配度最高的停车场
     *
     * @param lat     目标纬度；无效时按 poiName 回退
     * @param lng     目标经度；无效时按 poiName 回退
     * @param poiName 行程节点或区域名称
     * @param lang    输出语言，en / zh
     * @return 停车建议；无法匹配时 {@code hasParkingRecommendation = false}
     */
    ParkingInfo findNearbyParking(double lat, double lng, String poiName, String lang);
}
