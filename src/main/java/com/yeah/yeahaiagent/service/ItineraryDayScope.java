package com.yeah.yeahaiagent.service;

import java.time.LocalTime;
import java.util.Locale;

/**
 * 半日游 vs 一日游：一日游要排到晚上，落日不是终点。
 */
public final class ItineraryDayScope {

    /**
     * 仅作编排提示，绝不作为 APPEND 拒绝条件。
     * 半日游可自然扩到 5–6 站，一日游 7–8 站；真正约束是当天不超过 22:00。
     */
    public static final int HALF_DAY_SOFT_STOPS = 6;
    public static final int FULL_DAY_SOFT_STOPS = 8;
    public static final LocalTime EVENING_CURFEW = LocalTime.of(22, 0);

    /** @deprecated 不再作为硬上限，请用 {@link #HALF_DAY_SOFT_STOPS}。 */
    @Deprecated
    public static final int HALF_DAY_MAX_STOPS = HALF_DAY_SOFT_STOPS;
    /** @deprecated 不再作为硬上限，请用 {@link #FULL_DAY_SOFT_STOPS}。 */
    @Deprecated
    public static final int FULL_DAY_MAX_STOPS = FULL_DAY_SOFT_STOPS;

    private ItineraryDayScope() {
    }

    public static boolean isFullDay(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "一日游", "一日", "全天", "一整天", "玩一天", "待一天",
                "full day", "full-day", "wholeday", "whole day", "all day", "entire day");
    }

    /** 软提示站数，Refine / Validator 不得据此拒绝 APPEND。 */
    public static int softStopHint(String message) {
        return isFullDay(message) ? FULL_DAY_SOFT_STOPS : HALF_DAY_SOFT_STOPS;
    }

    /** @deprecated 名称像硬上限，实际只是软提示。 */
    @Deprecated
    public static int maxStops(String message) {
        return softStopHint(message);
    }

    public static boolean pastEveningCurfew(LocalTime arrival) {
        return arrival != null && arrival.isAfter(EVENING_CURFEW);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
