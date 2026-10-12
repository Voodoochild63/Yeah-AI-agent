package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.ParkingInfo;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 行程停车后处理：只写 Google Maps 检索词和分区贴士，不编造车库实体。
 */
@Component
public class ItineraryParkingAttacher {

    public void attach(ItineraryPlan plan, LanguageEnum language) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        boolean lastHadParking = false;
        for (int i = 0; i < schedule.size(); i++) {
            ItineraryNode node = schedule.get(i);
            if (node == null) {
                continue;
            }
            boolean walkingFromPrevious = i > 0 && isWalkingLeg(schedule.get(i - 1));
            if (walkingFromPrevious && lastHadParking) {
                node.setParkingSearchKeyword(null);
                node.setParkingTips(null);
                node.setParkingInfo(ParkingInfo.none(null));
                continue;
            }
            String keyword = resolveParkingSearchKeyword(node);
            String tips = resolveParkingTips(node, lang);
            node.setParkingSearchKeyword(keyword);
            node.setParkingTips(tips);
            node.setParkingInfo(ParkingInfo.mapsLink(keyword, tips));
            lastHadParking = true;
        }
    }

    private static String resolveParkingSearchKeyword(ItineraryNode node) {
        String existing = firstFilled(node.getParkingSearchKeyword(),
                node.getParkingInfo() == null ? null : node.getParkingInfo().parkingSearchKeyword());
        if (existing != null && existing.toLowerCase(Locale.ROOT).startsWith("parking near ")) {
            return existing.replaceAll("\\s+", " ").trim();
        }
        String poi = node.getPoiName() == null ? "" : node.getPoiName().trim();
        if (poi.isBlank()) {
            return "parking near Sydney";
        }
        String suburb = guessSuburb(poi);
        if (poi.toLowerCase(Locale.ROOT).contains(suburb.toLowerCase(Locale.ROOT))) {
            return "parking near " + poi;
        }
        return "parking near " + poi + " " + suburb;
    }

    private static String resolveParkingTips(ItineraryNode node, LanguageEnum language) {
        String existing = firstFilled(node.getParkingTips(),
                node.getParkingInfo() == null ? null : node.getParkingInfo().tip());
        return existing != null ? existing : defaultParkingTips(node.getPoiName(), language);
    }

    private static String defaultParkingTips(String poiName, LanguageEnum language) {
        String n = poiName == null ? "" : poiName.toLowerCase(Locale.ROOT);
        if (n.contains("grounds") || n.contains("westfield") || n.contains("broadway")
                || n.contains("mall") || n.contains("shopping")) {
            return language.text("建议停在园区专用访客车位或直属停车场",
                    "Use the venue visitor bays or the on-site car park.");
        }
        if (n.contains("circular quay") || n.contains("cbd") || n.contains("barangaroo")
                || n.contains("town hall") || n.contains("the rocks") || n.contains("haymarket")
                || n.contains("world square") || n.contains("opera")) {
            return language.text("市中心车位紧张，建议提前预订商业车库",
                    "CBD bays are tight; book a commercial garage ahead.");
        }
        if (n.contains("bexley") || n.contains("marrickville") || n.contains("newtown")
                || n.contains("enmore") || n.contains("dulwich") || n.contains("leichhardt")
                || n.contains("stanmore") || n.contains("tempe")) {
            return language.text("居民街区建议寻找路边 1-2 小时限时或免费车位",
                    "On residential streets, look for 1-2 hour or free kerbside parking.");
        }
        return language.text("建议寻找周边路边或就近车位",
                "Look for nearby street or lot parking.");
    }

    private static String guessSuburb(String poiName) {
        String normalized = poiName.toLowerCase(Locale.ROOT);
        String[] suburbs = {
                "Alexandria", "Bexley", "Marrickville", "Newtown", "Chippendale", "Surry Hills",
                "Haymarket", "The Rocks", "Bondi", "Bronte", "Glebe", "Paddington", "Darlinghurst",
                "Redfern", "Waterloo", "Zetland", "Enmore", "Randwick", "Coogee", "Kensington",
                "Kingsford", "Maroubra", "Clovelly"
        };
        for (String suburb : suburbs) {
            if (normalized.contains(suburb.toLowerCase(Locale.ROOT))) {
                return suburb;
            }
        }
        return "Sydney";
    }

    private static boolean isWalkingLeg(ItineraryNode previous) {
        if (previous == null) {
            return false;
        }
        if (ItineraryTransitPlanner.MODE_DRIVING.equals(previous.getSelectedMode())
                || ItineraryTransitPlanner.MODE_TRANSIT.equals(previous.getSelectedMode())) {
            return false;
        }
        if (ItineraryTransitPlanner.MODE_WALKING.equals(previous.getSelectedMode())) {
            return true;
        }
        return isWalkingTransit(previous.getTransitToNext());
    }

    private static boolean isWalkingTransit(String transit) {
        if (transit == null || transit.isBlank()) {
            return false;
        }
        String normalized = transit.toLowerCase(Locale.ROOT);
        return normalized.contains("步行")
                || normalized.contains("walk")
                || normalized.contains("on foot")
                || normalized.contains("walkable");
    }

    private static String firstFilled(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }
}
