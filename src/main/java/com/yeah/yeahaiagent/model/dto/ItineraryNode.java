package com.yeah.yeahaiagent.model.dto;

import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
     * 下一站出行方式：WALKING / DRIVING / TRANSIT。
     * 默认由后处理按直线距离预设（≤1.2km 步行，否则驾车）。
     */
    private String selectedMode;

    /**
     * 三种出行方式的预估，一次下发供前端即时切换。
     */
    private TransitOptions transitOptions;

    /** Google Maps / Serper 解析出的纬度，后处理算路用。 */
    private Double latitude;

    /** Google Maps / Serper 解析出的经度，后处理算路用。 */
    private Double longitude;

    /** 后处理推算的到达时刻，格式 HH:mm。 */
    private String estimatedArrival;

    /** OPEN / CLOSING_SOON / CLOSED / UNKNOWN */
    private String openStatus;

    /** 给前端的开闭店提示文案。 */
    private String statusWarning;

    /** 当天匹配到的开门时刻 HH:mm，切换出行方式后前端可重算。 */
    private String opensAt;

    /** 当天匹配到的打烊时刻 HH:mm。 */
    private String closesAt;

    /** 友好营业时间文案，例如 "今日 07:00–15:00" 或 "全天开放"。 */
    private String openingHoursText;

    /** Serper 一次召回预置的 2~3 个同类备选，前端原地轮播。 */
    private List<SpotCandidateDto> candidates;

    /** 落日专属候选，通常与 candidates 同步，供前端切换观景点。 */
    private List<SpotCandidateDto> sunsetCandidates;

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

    /**
     * 结构化品类码：WALK / SCENIC / PARK / ATTRACTION / BAR / DESSERT / SHOPPING / DINING / CAFE / TEA / SUNSET。
     */
    private String spotCategory;

    /** 该站及后续禁止推荐自驾（酒吧/饮酒场景）。 */
    private Boolean drivingForbidden;

    /** 该站涉及饮酒（酒吧、Pub、Brewery、Nightclub）。 */
    private Boolean alcoholInvolved;

    /** 安全出行提示，例如酒驾警示。 */
    private String safetyWarning;

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
