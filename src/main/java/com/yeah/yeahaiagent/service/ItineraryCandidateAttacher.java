package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.dto.SpotCandidateDto;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.util.GeoDistanceUtils;
import com.yeah.yeahaiagent.util.OpeningHoursParser;
import com.yeah.yeahaiagent.util.OpeningHoursParser.DayHours;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把 Serper 一次召回里的 Top-3 合规店挂到节点上，Shuffle 不再二次请求。
 */
@Component
@Slf4j
public class ItineraryCandidateAttacher {

    static final int MAX_CANDIDATES = 3;
    static final double MIN_RATING = 4.0d;

    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    private final Clock clock;

    public ItineraryCandidateAttacher() {
        this(Clock.system(SYDNEY));
    }

    ItineraryCandidateAttacher(Clock clock) {
        this.clock = clock == null ? Clock.system(SYDNEY) : clock;
    }

    public void attach(ItineraryPlan plan, List<RealPlaceDto> places, LanguageEnum language) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        DayOfWeek day = clock.instant().atZone(SYDNEY).getDayOfWeek();
        List<RealPlaceDto> eligible = eligiblePlaces(places);
        for (int i = 0; i < schedule.size(); i++) {
            ItineraryNode node = schedule.get(i);
            if (node == null) {
                continue;
            }
            Set<String> occupied = occupiedNames(schedule, i);
            List<SpotCandidateDto> candidates = pickCandidates(node, eligible, occupied, day, lang);
            bindCandidates(node, candidates);
        }
    }

    /**
     * 只给指定下标挂备选，局部替换时不要打乱其余卡片的候选池。
     */
    public void attachOne(ItineraryPlan plan, int index, List<RealPlaceDto> places, LanguageEnum language) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || index < 0 || index >= schedule.size() || schedule.get(index) == null) {
            return;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        DayOfWeek day = clock.instant().atZone(SYDNEY).getDayOfWeek();
        ItineraryNode node = schedule.get(index);
        List<SpotCandidateDto> candidates = pickCandidates(
                node, eligiblePlaces(places), occupiedNames(schedule, index), day, lang);
        bindCandidates(node, candidates);
    }

    private static void bindCandidates(ItineraryNode node, List<SpotCandidateDto> candidates) {
        node.setCandidates(candidates);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        SpotCandidateDto current = candidates.get(0);
        if (node.getLatitude() == null) {
            node.setLatitude(current.getLatitude());
        }
        if (node.getLongitude() == null) {
            node.setLongitude(current.getLongitude());
        }
        log.info("candidates ready. poi={} size={}", node.getPoiName(), candidates.size());
    }

    List<SpotCandidateDto> pickCandidates(ItineraryNode node,
                                          List<RealPlaceDto> eligible,
                                          Set<String> occupied,
                                          DayOfWeek day,
                                          LanguageEnum language) {
        List<SpotCandidateDto> picked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        RealPlaceDto matched = ItineraryOpeningHoursChecker.matchPlace(node.getPoiName(), eligible);
        SpotCandidateDto current = matched != null
                ? toCandidate(matched, day, language)
                : snapshotNode(node, language);
        if (current != null) {
            addIfNew(picked, seen, current);
        }
        List<RealPlaceDto> ranked = new ArrayList<>(eligible);
        ranked.sort(Comparator.comparing(RealPlaceDto::getRating, Comparator.nullsLast(Comparator.reverseOrder())));
        String category = categoryHint(node, matched);
        ranked.sort(Comparator.comparingInt((RealPlaceDto place) -> categoryScore(place, category)).reversed());
        for (RealPlaceDto place : ranked) {
            if (picked.size() >= MAX_CANDIDATES) {
                break;
            }
            String key = key(place.getTitle());
            if (key.isBlank() || occupied.contains(key) || seen.contains(key)) {
                continue;
            }
            addIfNew(picked, seen, toCandidate(place, day, language));
        }
        return picked;
    }

    static List<RealPlaceDto> eligiblePlaces(List<RealPlaceDto> places) {
        if (places == null || places.isEmpty()) {
            return List.of();
        }
        List<RealPlaceDto> eligible = new ArrayList<>();
        for (RealPlaceDto place : places) {
            if (place == null || place.getTitle() == null || place.getTitle().isBlank()) {
                continue;
            }
            if (place.getRating() == null || place.getRating() < MIN_RATING) {
                continue;
            }
            if (place.getLatitude() == null || place.getLongitude() == null
                    || !GeoDistanceUtils.isValidCoord(place.getLatitude(), place.getLongitude())) {
                continue;
            }
            eligible.add(place);
        }
        return eligible;
    }

    private static Set<String> occupiedNames(List<ItineraryNode> schedule, int index) {
        Set<String> occupied = new HashSet<>();
        for (int i = 0; i < schedule.size(); i++) {
            if (i == index || schedule.get(i) == null) {
                continue;
            }
            String key = key(schedule.get(i).getPoiName());
            if (!key.isBlank()) {
                occupied.add(key);
            }
        }
        return occupied;
    }

    private static boolean addIfNew(List<SpotCandidateDto> picked, Set<String> seen, SpotCandidateDto candidate) {
        if (candidate == null || candidate.getName() == null || candidate.getName().isBlank()) {
            return false;
        }
        String nameKey = key(candidate.getName());
        if (seen.contains(nameKey)) {
            return false;
        }
        if (candidate.getLatitude() == null || candidate.getLongitude() == null
                || !GeoDistanceUtils.isValidCoord(candidate.getLatitude(), candidate.getLongitude())) {
            return false;
        }
        seen.add(nameKey);
        picked.add(candidate);
        return true;
    }

    private SpotCandidateDto toCandidate(RealPlaceDto place, DayOfWeek day, LanguageEnum language) {
        DayHours hours = OpeningHoursParser.forDay(place.getOpeningHours(), day);
        String text = OpeningHoursParser.friendlyText(hours, place.getOpeningHours(), language.isZh());
        if (text == null || text.isBlank()) {
            text = place.getOpeningHoursText();
        }
        return new SpotCandidateDto(
                place.getTitle().trim(),
                place.getCategory(),
                place.getRating(),
                place.getRatingCount(),
                place.getLatitude(),
                place.getLongitude(),
                text,
                place.getAddress(),
                highlightDesc(place.getCategory(), language));
    }

    private static SpotCandidateDto snapshotNode(ItineraryNode node, LanguageEnum language) {
        if (node.getPoiName() == null || node.getPoiName().isBlank()) {
            return null;
        }
        if (node.getLatitude() == null || node.getLongitude() == null
                || !GeoDistanceUtils.isValidCoord(node.getLatitude(), node.getLongitude())) {
            return null;
        }
        String category = firstFilled(node.getCategory(), node.getCategoryEn(), node.getCategoryZh());
        return new SpotCandidateDto(
                node.getPoiName().trim(),
                category,
                null,
                null,
                node.getLatitude(),
                node.getLongitude(),
                node.getOpeningHoursText(),
                null,
                highlightDesc(category, language));
    }

    private static String categoryHint(ItineraryNode node, RealPlaceDto matched) {
        if (matched != null && matched.getCategory() != null && !matched.getCategory().isBlank()) {
            return matched.getCategory();
        }
        return firstFilled(node.getCategory(), node.getCategoryEn(), node.getCategoryZh());
    }

    private static int categoryScore(RealPlaceDto place, String hint) {
        if (hint == null || hint.isBlank() || place.getCategory() == null) {
            return 0;
        }
        String left = hint.toLowerCase(Locale.ROOT);
        String right = place.getCategory().toLowerCase(Locale.ROOT);
        if (left.equals(right) || left.contains(right) || right.contains(left)) {
            return 2;
        }
        return 0;
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

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String highlightDesc(String category, LanguageEnum language) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String tag = category.trim();
        String lower = tag.toLowerCase(Locale.ROOT);
        boolean zh = language == null || language.isZh();
        if (containsAny(lower, "cafe", "coffee", "brunch", "bakery")) {
            return zh ? "享受地道 Brunch" : "neighbourhood brunch";
        }
        if (containsAny(lower, "gallery", "museum", "art")) {
            return zh ? "逛独立艺廊" : "independent gallery hop";
        }
        if (containsAny(lower, "park", "lookout", "beach", "harbour", "harbor")) {
            return zh ? "拍城市光影" : "golden-hour views";
        }
        if (containsAny(lower, "restaurant", "dining", "bar", "wine")) {
            return zh ? "安排一顿晚餐" : "a proper sit-down meal";
        }
        return zh ? "打卡 " + tag : tag;
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
