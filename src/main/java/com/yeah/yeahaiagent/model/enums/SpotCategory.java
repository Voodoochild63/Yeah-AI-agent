package com.yeah.yeahaiagent.model.enums;

import java.util.Locale;

/**
 * 行程节点品类。意图解析、检索词与停留时长都按这个分流，避免散步被搜成餐厅。
 */
public enum SpotCategory {
    WALK("散步 / 观景", "Walk / Scenic", 40),
    SCENIC("观景", "Scenic lookout", 45),
    PARK("公园散步", "Park stroll", 40),
    ATTRACTION("展览打卡", "Gallery / Exhibition", 45),
    BAR("酒吧", "Bar", 60),
    PUB("酒吧", "Pub", 60),
    DESSERT("甜品", "Dessert", 25),
    SHOPPING("购物", "Shopping", 45),
    KOREAN_RESTAURANT("韩餐", "Korean restaurant", 75),
    DINING("餐厅", "Dining", 75),
    CAFE("咖啡馆", "Cafe", 45),
    TEA("下午茶", "Afternoon tea", 45),
    SUNSET("落日观景", "Sunset lookout", 45),
    OTHER("打卡", "Stop", 45);

    private final String labelZh;
    private final String labelEn;
    private final int stayMinutes;

    SpotCategory(String labelZh, String labelEn, int stayMinutes) {
        this.labelZh = labelZh;
        this.labelEn = labelEn;
        this.stayMinutes = stayMinutes;
    }

    public String labelZh() {
        return labelZh;
    }

    public String labelEn() {
        return labelEn;
    }

    public String label(LanguageEnum language) {
        return language != null && language.isZh() ? labelZh : labelEn;
    }

    public int stayMinutes() {
        return stayMinutes;
    }

    public boolean scenicWalk() {
        return this == WALK || this == SCENIC || this == PARK;
    }

    public boolean longDining() {
        return this == DINING || this == KOREAN_RESTAURANT;
    }

    public boolean alcohol() {
        return this == BAR || this == PUB;
    }

    public boolean usesScenicSearch() {
        return scenicWalk() || this == SUNSET || this == ATTRACTION;
    }

    public static SpotCategory from(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER;
        }
        String key = raw.trim();
        for (SpotCategory value : values()) {
            if (value.name().equalsIgnoreCase(key)) {
                return value;
            }
        }
        return fromText(key);
    }

    public static SpotCategory fromText(String raw) {
        String blob = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (looksLikeKorean(blob)) {
            return KOREAN_RESTAURANT;
        }
        if (looksLikeDessert(blob) && !looksLikeDiningMeal(blob)) {
            return DESSERT;
        }
        boolean walk = looksLikeWalk(blob);
        boolean dining = looksLikeDining(blob);
        if (walk && !dining && !looksLikeDessert(blob) && !looksLikeEdiblePayload(blob)) {
            if (containsAny(blob, "公园", "绿地") || containsWord(blob, "park") || containsWord(blob, "reserve")) {
                return PARK;
            }
            return WALK;
        }
        if (looksLikeBar(blob) && !dining && !looksLikeEdiblePayload(blob)) {
            return containsWord(blob, "pub") || containsWord(blob, "pubs") ? PUB : BAR;
        }
        if (containsAny(blob, "gallery", "museum", "exhibition", "看展", "艺廊", "美术", "展览") && !dining) {
            return ATTRACTION;
        }
        if (containsAny(blob, "shopping", "boutique", "商场", "购物", "买买") && !dining) {
            return SHOPPING;
        }
        if (containsAny(blob, "afternoon tea", "high tea", "下午茶")) {
            return TEA;
        }
        if (containsAny(blob, "cafe", "coffee", "brunch", "bakery", "咖啡") && !containsAny(blob, "晚饭", "晚餐", "dinner")) {
            return CAFE;
        }
        if (dining) {
            return DINING;
        }
        if (containsAny(blob, "sunset", "落日", "日落", "golden hour", "晚霞")) {
            return SUNSET;
        }
        if (containsAny(blob, "公园", "绿地") || containsWord(blob, "park") || containsWord(blob, "reserve")
                || containsWord(blob, "garden")) {
            return PARK;
        }
        if (containsAny(blob, "lookout", "headland", "harbour view", "观景", "scenic")) {
            return SCENIC;
        }
        if (walk && !looksLikeEdiblePayload(blob)) {
            return WALK;
        }
        return OTHER;
    }

    public static boolean looksLikeKorean(String blob) {
        return containsAny(blob, "korean", "韩料", "韩餐", "韩食", "韩国菜", "韩国料理", "k-bbq", "kbbq");
    }

    public static boolean looksLikeDessert(String blob) {
        return containsAny(blob, "dessert", "sweets", "ice cream", "gelato", "甜品", "甜食", "冰淇淋", "冰激凌");
    }

    /**
     * 用户真正要搜的是吃的/喝的，即使句子里顺带提到了散步锚点。
     */
    public static boolean looksLikeEdiblePayload(String blob) {
        if (blob == null || blob.isBlank()) {
            return false;
        }
        return looksLikeKorean(blob) || looksLikeDessert(blob) || looksLikeDiningMeal(blob)
                || containsAny(blob, "吃", "餐厅", "正餐", "晚饭", "晚餐", "午餐", "cafe", "coffee", "咖啡",
                "gelato", "restaurant");
    }

    public static boolean looksLikeDiningMeal(String blob) {
        return containsAny(blob,
                "restaurant", "dining", "dinner", "lunch", "supper", "japanese", "korean",
                "bbq", "sushi", "ramen", "izakaya", "yakiniku", "steak", "noodle",
                "日料", "烤肉", "晚饭", "晚餐", "午餐", "正餐", "餐厅", "韩料", "韩餐");
    }

    public static boolean looksLikeWalk(String blob) {
        return containsAny(blob,
                "散步", "走走", "随便走", "吹海风", "吹风", "夜逛", "海港散步", "海边走走",
                "walk", "stroll", "promenade", "foreshore", "harbour walk", "coastal walk",
                "night walk", "wander");
    }

    public static boolean looksLikeBar(String blob) {
        if (blob == null || blob.isBlank()) {
            return false;
        }
        String lower = blob.toLowerCase(Locale.ROOT);
        if (containsAny(lower,
                "barangaroo", "barista", "barbecue", "barber",
                "coffee bar", "juice bar", "salad bar", "snack bar", "oyster bar", "chocolate bar")) {
            return containsWord(lower, "pub") || containsWord(lower, "pubs")
                    || containsWord(lower, "brewery") || containsWord(lower, "breweries")
                    || containsWord(lower, "tavern") || containsWord(lower, "taverns")
                    || containsWord(lower, "cocktail") || containsWord(lower, "cocktails")
                    || containsAny(lower, "酒吧", "清吧", "酒馆", "夜店");
        }
        if (containsAny(lower, "酒吧", "清吧", "酒馆", "夜店", "nightclub")) {
            return true;
        }
        return containsWord(lower, "bar") || containsWord(lower, "bars")
                || containsWord(lower, "pub") || containsWord(lower, "pubs")
                || containsWord(lower, "brewery") || containsWord(lower, "breweries")
                || containsWord(lower, "tavern") || containsWord(lower, "taverns")
                || containsWord(lower, "cocktail") || containsWord(lower, "cocktails");
    }

    public static boolean looksLikeDining(String blob) {
        return looksLikeDiningMeal(blob) || containsAny(blob, "吃饭", "吃顿");
    }

    public static boolean looksLikeDiningPlace(String title, String category) {
        String blob = (nullToEmpty(title) + " " + nullToEmpty(category)).toLowerCase(Locale.ROOT);
        if (containsAny(blob, "park", "lookout", "reserve", "walk", "trail", "garden", "beach",
                "headland", "promenade", "gallery", "museum", "library")) {
            return false;
        }
        return looksLikeDining(blob) || containsAny(blob, "cafe", "coffee", "bakery", "pizza",
                "takeaway", "bistro", "eatery", "kitchen");
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (needle != null && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String haystack, String word) {
        return haystack.matches(".*\\b" + word + "\\b.*");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
