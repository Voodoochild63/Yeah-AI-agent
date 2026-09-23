package com.yeah.yeahaiagent.model.dto;

import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个行程节点（地点、时间、交通）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryNode {

    private String nodeId;

    private String poiName;

    private String category;

    /** 中文标签，例如 "早午餐与绿植园区"。 */
    private String categoryZh;

    /** 英文标签，例如 "Brunch & Garden"。 */
    private String categoryEn;

    private String arrivalTime;

    private String stayDuration;

    private String transitToNext;

    /**
     * 小红书 / RedNote 检索词：官方英文店名 +「拍照 机位 避坑」。
     */
    private String searchKeyword;

    /**
     * Reddit 检索词：纯英文店名、城市与品类评价。
     */
    private String redditKeyword;

    /**
     * 官方网站。高置信度时为真实官网，否则可为 Google 直达链接，不确定时留空。
     */
    private String websiteUrl;

    /**
     * 轻量停车常识，例如“居民街区建议在 Shaw St 寻找路边车位”。
     */
    private String parkingTips;

    /**
     * Google Maps 停车检索词，格式：parking near 官方英文店名 街区。
     */
    private String parkingSearchKeyword;

    /**
     * 节点停车增强信息。由服务端在大模型返回后挂载，不依赖模型填写具体车库名。
     */
    private ParkingInfo parkingInfo;

    public ItineraryNode(String nodeId, String poiName, String category,
                         String arrivalTime, String stayDuration, String transitToNext) {
        this.nodeId = nodeId;
        this.poiName = poiName;
        this.category = category;
        this.arrivalTime = arrivalTime;
        this.stayDuration = stayDuration;
        this.transitToNext = transitToNext;
    }

    public String localizedCategory(LanguageEnum language) {
        return ItineraryPlan.pick(language, categoryZh, categoryEn, category);
    }
}
