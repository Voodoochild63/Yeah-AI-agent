package com.yeah.yeahaiagent.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * POI 检索服务：基于本地标签、氛围与视觉风格匹配，不调用远程 Embedding
 */
@Service
public class PoiRagService {

    public record PoiItem(
            String poiName,
            String category,
            List<String> tags,
            List<String> vibes,
            List<String> visualStyle,
            String personalTip,
            String description
    ) {
    }

    private static final String MODEL_USAGE_HINT =
            "若选用了带有打卡小贴士的地点，请在行程建议（summary 或 backupSuggestions）中自然融入该特色";

    private final List<PoiItem> poiCache = new ArrayList<>();

    @PostConstruct
    public void initMockData() {
        poiCache.clear();
        poiCache.add(new PoiItem(
                "Single O Surry Hills",
                "Brunch/精品咖啡",
                List.of("Surry Hills", "手冲咖啡", "街头轻食"),
                List.of("清晨阳光", "社区感", "活力"),
                List.of("街边高饱和长椅", "自然光影"),
                "Reservoir St 巷子口外带杯位置极佳，手冲单品豆风味层次明显。",
                "悉尼 Surry Hills 标志性精品咖啡馆，以卓越的单品手冲和舒适街头氛围闻名"
        ));
        poiCache.add(new PoiItem(
                "The Record Store",
                "独立唱片店",
                List.of("Surry Hills", "黑胶", "Soul", "Funk", "Jazz"),
                List.of("Neo-Soul", "复古律动", "沉浸淘盘"),
                List.of("黑胶封套墙", "室内暖调反差"),
                "Soul/R&B 专区经常能翻到罕见的黑胶盘，背景常年放经典 Groove 音乐。",
                "Surry Hills 经典独立黑胶唱片店，藏有丰富的 Soul、Funk、Jazz 黑胶，音乐氛围浓郁"
        ));
        poiCache.add(new PoiItem(
                "White Rabbit Gallery & Tea House",
                "当代艺术馆/茶空间",
                List.of("Chippendale", "当代艺术", "冷萃茶", "点心"),
                List.of("静谧思考", "禅意", "设计感"),
                List.of("大面积留白", "建筑几何构图"),
                "逛完楼上先锋展后，在一楼茶歇喝一杯高山乌龙，非常适合整理灵感。",
                "位于 Chippendale 白兔美术馆内，当代艺术展览丰富，附带极具设计感的茶歇空间"
        ));
        poiCache.add(new PoiItem(
                "Edition Coffee Roasters",
                "日式北欧风咖啡",
                List.of("Haymarket", "日式轻食", "单品手冲"),
                List.of("极简日系", "安宁", "炭烧香气"),
                List.of("暗调和风", "光影木质质感"),
                "极简暗黑色调装潢，舒芙蕾松饼与浅烘手冲是绝配。",
                "Haymarket 日式北欧风咖啡馆，暗黑极简装潢，适合安静手冲与轻食"
        ));
    }

    /**
     * 按用户查询做本地标签/氛围/视觉风格加权匹配
     *
     * @param query 用户查询字符串
     * @return 得分最高的 top 3 打卡点格式化文本；无结果时返回空字符串
     */
    public String searchPoi(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<PoiItem> topMatches = poiCache.stream()
                .map(item -> new ScoredPoi(item, score(item, normalizedQuery)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredPoi::score).reversed())
                .limit(3)
                .map(ScoredPoi::item)
                .toList();
        if (topMatches.isEmpty()) {
            return "";
        }
        String body = topMatches.stream()
                .map(this::formatPoi)
                .collect(Collectors.joining("\n\n"));
        return body + "\n\n" + MODEL_USAGE_HINT;
    }

    /**
     * 按店名查私域打卡贴士，供行程导出等场景补充个性化内容
     *
     * @param poiName 行程节点中的店名
     * @return 命中的私藏贴士；未命中返回 null
     */
    public String findPersonalTip(String poiName) {
        if (poiName == null || poiName.isBlank()) {
            return null;
        }
        String normalized = poiName.toLowerCase(Locale.ROOT).trim();
        return poiCache.stream()
                .filter(item -> {
                    String cached = item.poiName().toLowerCase(Locale.ROOT);
                    return cached.contains(normalized) || normalized.contains(cached);
                })
                .map(PoiItem::personalTip)
                .filter(tip -> tip != null && !tip.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * 用户话里点名的具体店铺。匹配完整店名，也匹配去掉 & 后缀的简称。
     */
    public String findNamedVenue(String query) {
        if (query == null || query.isBlank() || poiCache.isEmpty()) {
            return null;
        }
        String haystack = query.toLowerCase(Locale.ROOT);
        String bestName = null;
        int bestIndex = Integer.MAX_VALUE;
        int bestLength = 0;
        for (PoiItem item : poiCache) {
            for (String alias : venueAliases(item.poiName())) {
                int index = haystack.indexOf(alias.toLowerCase(Locale.ROOT));
                if (index < 0) {
                    continue;
                }
                if (index < bestIndex || (index == bestIndex && alias.length() > bestLength)) {
                    bestName = item.poiName();
                    bestIndex = index;
                    bestLength = alias.length();
                }
            }
        }
        return bestName;
    }

    private static List<String> venueAliases(String poiName) {
        if (poiName == null || poiName.isBlank()) {
            return List.of();
        }
        String shortened = poiName.replaceAll("\\s*&.*$", "").trim();
        if (shortened.isBlank() || shortened.equalsIgnoreCase(poiName)) {
            return List.of(poiName);
        }
        return List.of(poiName, shortened);
    }

    private int score(PoiItem item, String query) {
        int score = 0;
        if (containsIgnoreCase(query, item.poiName())) {
            score += 8;
        }
        if (containsIgnoreCase(query, item.category())) {
            score += 4;
        }
        if (containsIgnoreCase(query, item.description())) {
            score += 2;
        }
        if (containsIgnoreCase(query, item.personalTip())) {
            score += 2;
        }
        score += matchListScore(query, item.tags(), 5);
        score += matchListScore(query, item.vibes(), 5);
        score += matchListScore(query, item.visualStyle(), 5);
        return score;
    }

    private int matchListScore(String query, List<String> keywords, int weight) {
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String keyword : keywords) {
            if (containsIgnoreCase(query, keyword)) {
                score += weight;
            }
        }
        return score;
    }

    private boolean containsIgnoreCase(String query, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        return query.contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String formatPoi(PoiItem item) {
        String vibeAndVisual = "氛围：" + String.join("、", item.vibes())
                + "；摄影：" + String.join("、", item.visualStyle());
        return """
                【店名】%s
                【类别】%s
                【标签】%s
                【氛围与摄影属性】%s
                【打卡小贴士】%s
                """.formatted(
                item.poiName(),
                item.category(),
                String.join(", ", item.tags()),
                vibeAndVisual,
                item.personalTip()
        ).trim();
    }

    private record ScoredPoi(PoiItem item, int score) {
    }
}
