package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.ParkingInfo;
import com.yeah.yeahaiagent.model.dto.TransitOptions;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.model.enums.SpotCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 仅对真正的酒吧节点切断自驾。午餐、咖啡馆、公园、日落观景不得带饮酒警示。
 */
@Component
@Slf4j
public class ItinerarySafetyGuardrail {

    static final String WARNING_ZH = "🍷 涉及饮酒，请使用公共交通或网约车出行，切勿酒驾";
    static final String WARNING_EN = "🍷 Alcohol involved — use walking, public transport or Uber. Never drink and drive.";
    static final String TRANSIT_HINT_ZH = "步行 / 公共交通（Bus/Light Rail） / Uber 网约车";
    static final String TRANSIT_HINT_EN = "Walk / public transport (Bus/Light Rail) / Uber";

    public void apply(ItineraryPlan plan, LanguageEnum language) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        for (ItineraryNode node : schedule) {
            clearInnocentAlcoholFlags(node);
        }
        for (int i = 0; i < schedule.size(); i++) {
            ItineraryNode node = schedule.get(i);
            if (!isAlcoholVenue(node)) {
                continue;
            }
            node.setAlcoholInvolved(true);
            node.setSafetyWarning(lang.text(WARNING_ZH, WARNING_EN));
            node.setParkingSearchKeyword(null);
            node.setParkingTips(null);
            node.setParkingInfo(ParkingInfo.none(null));
            node.setDrivingForbidden(true);
            if (i > 0) {
                forbidDrivingOnHop(schedule.get(i - 1), lang);
            }
            log.info("alcohol safety applied on bar only. index={} poi={}", i, node.getPoiName());
        }
    }

    public static boolean isAlcoholVenue(ItineraryNode node) {
        if (node == null) {
            return false;
        }
        String spot = trim(node.getSpotCategory());
        if ("BAR".equalsIgnoreCase(spot) || "PUB".equalsIgnoreCase(spot)) {
            return true;
        }
        SpotCategory labeled = SpotCategory.from(spot);
        if (labeled.alcohol()) {
            return true;
        }
        if (labeled == SpotCategory.SUNSET || labeled.scenicWalk()
                || labeled == SpotCategory.CAFE || labeled == SpotCategory.TEA
                || labeled == SpotCategory.DESSERT || labeled == SpotCategory.DINING
                || labeled == SpotCategory.KOREAN_RESTAURANT
                || labeled == SpotCategory.ATTRACTION || labeled == SpotCategory.SHOPPING) {
            return false;
        }
        String type = String.join(" ",
                nullToEmpty(node.getCategory()),
                nullToEmpty(node.getCategoryZh()),
                nullToEmpty(node.getCategoryEn()),
                nullToEmpty(node.getSpotCategory())).toLowerCase(Locale.ROOT);
        return containsBarOrPubType(type);
    }

    static boolean containsBarOrPubType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        if (type.contains("barangaroo") || type.contains("barista") || type.contains("barbecue")) {
            return false;
        }
        return type.contains("酒吧") || type.contains("清吧") || type.contains("酒馆")
                || type.matches(".*\\bpubs?\\b.*") || type.matches(".*\\bbars?\\b.*");
    }

    static void clearInnocentAlcoholFlags(ItineraryNode node) {
        if (node == null) {
            return;
        }
        if (isAlcoholVenue(node)) {
            return;
        }
        node.setAlcoholInvolved(false);
        if (isDrinkDriveWarning(node.getSafetyWarning())) {
            node.setSafetyWarning(null);
        }
        node.setDrivingForbidden(false);
    }

    static boolean isDrinkDriveWarning(String warning) {
        if (warning == null || warning.isBlank()) {
            return false;
        }
        String lower = warning.toLowerCase(Locale.ROOT);
        return lower.contains("酒驾") || lower.contains("饮酒") || lower.contains("alcohol")
                || lower.contains("drink and drive");
    }

    private static void forbidDrivingOnHop(ItineraryNode from, LanguageEnum language) {
        if (from == null) {
            return;
        }
        from.setDrivingForbidden(true);
        TransitOptions options = from.getTransitOptions();
        if (options != null) {
            options.setDrivingAllowed(false);
        }
        String mode = from.getSelectedMode();
        if (ItineraryTransitPlanner.MODE_DRIVING.equals(mode) || mode == null || mode.isBlank()) {
            boolean walkable = options != null
                    && options.getWalking() != null
                    && options.getWalking().getDurationMinutes() != null
                    && options.getWalking().getDurationMinutes() <= 20;
            String replacement = walkable
                    ? ItineraryTransitPlanner.MODE_WALKING
                    : ItineraryTransitPlanner.MODE_TRANSIT;
            from.setSelectedMode(replacement);
            from.setTransitToNext(ItineraryTransitPlanner.formatSelected(options, replacement, language)
                    + " · " + language.text(TRANSIT_HINT_ZH, TRANSIT_HINT_EN));
        } else {
            String current = from.getTransitToNext();
            String hint = language.text(TRANSIT_HINT_ZH, TRANSIT_HINT_EN);
            if (current == null || !current.contains("Uber")) {
                from.setTransitToNext((current == null || current.isBlank() ? hint : current + " · " + hint));
            }
        }
    }

    private static String nameAndType(ItineraryNode node) {
        return String.join(" ",
                nullToEmpty(node.getPoiName()),
                nullToEmpty(node.getCategory()),
                nullToEmpty(node.getCategoryZh()),
                nullToEmpty(node.getCategoryEn()),
                nullToEmpty(node.getSpotCategory()));
    }

    private static String firstFilled(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
