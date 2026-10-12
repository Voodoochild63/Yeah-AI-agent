package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.CustomPoiDto;
import com.yeah.yeahaiagent.model.dto.RagContext;
import com.yeah.yeahaiagent.model.dto.TasteProfile;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 把规划请求压成 suburb + theme + timeOfDay 指纹，供骨架缓存与并发合并使用。
 */
public final class ItineraryRequestFingerprint {

    private static final Pattern FILLER = Pattern.compile(
            "[\\p{Punct}。！？、，]+|帮我|安排|规划|想去|想要|please|plan ");

    private ItineraryRequestFingerprint() {
    }

    public static String key(String message, String lang, RagContext ragContext) {
        String raw = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String lower = normalize(message);
        return suburb(raw)
                + "|" + theme(lower)
                + "|" + timeOfDay(message)
                + "|" + LanguageEnum.fromCode(lang).getCode()
                + "|" + ragHash(ragContext)
                + "|" + lower;
    }

    static String suburb(String lower) {
        return SydneyPlaceLocator.guessSuburb(lower).toLowerCase(Locale.ROOT);
    }

    static String theme(String lower) {
        if (containsAny(lower, "gelato", "dessert", "甜品", "冰淇淋")) {
            return "dessert";
        }
        if (containsAny(lower, "coffee", "cafe", "咖啡")) {
            return "coffee";
        }
        if (containsAny(lower, "brunch", "早午餐", "早餐")) {
            return "brunch";
        }
        if (containsAny(lower, "gallery", "art", "看展", "艺廊", "美术")) {
            return "art";
        }
        if (containsAny(lower, "walk", "stroll", "散步", "海边", "coastal", "beach", "吹海风")) {
            return "coastal";
        }
        if (containsAny(lower, "dinner", "lunch", "日料", "韩", "烤肉", "restaurant", "晚饭", "晚餐")) {
            return "dining";
        }
        return "general";
    }

    static String timeOfDay(String message) {
        if (ItineraryDayScope.isFullDay(message)) {
            return "full-day";
        }
        String lower = normalize(message);
        if (containsAny(lower, "晚上", "夜间", "夜市", "evening", "night")) {
            return "evening";
        }
        return "half-day";
    }

    static String normalize(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String collapsed = FILLER.matcher(message.toLowerCase(Locale.ROOT).trim()).replaceAll(" ");
        return collapsed.replaceAll("\\s+", " ").trim();
    }

    private static String ragHash(RagContext ragContext) {
        if (ragContext == null) {
            return "-";
        }
        TasteProfile taste = ragContext.getTasteProfile();
        StringBuilder builder = new StringBuilder();
        if (taste != null) {
            builder.append(nullToEmpty(taste.getAesthetic()))
                    .append('/')
                    .append(nullToEmpty(taste.getPace()))
                    .append('/')
                    .append(nullToEmpty(taste.getPhotoStyle()));
        }
        if (ragContext.getCustomPois() != null) {
            for (CustomPoiDto poi : ragContext.getCustomPois()) {
                if (poi != null && poi.getPoiName() != null && !poi.getPoiName().isBlank()) {
                    builder.append(',').append(poi.getPoiName().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        String raw = builder.toString().trim();
        return raw.isBlank() ? "-" : raw;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
