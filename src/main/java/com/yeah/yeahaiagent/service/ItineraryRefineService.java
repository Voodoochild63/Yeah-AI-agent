package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.ItineraryRefineIntent;
import com.yeah.yeahaiagent.model.dto.ItineraryRefineResponse;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.enums.ActionType;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.model.enums.SpotCategory;
import com.yeah.yeahaiagent.util.GeoDistanceUtils;
import com.yeah.yeahaiagent.util.OpeningHoursParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 会话内局部打补丁：REPLACE 只换一张卡片，APPEND 在锚点之后插入新站，再重算局部通勤与营业状态。
 */
@Service
@Slf4j
public class ItineraryRefineService {

    static final double PREFER_NEAR_KM = 4.0d;
    static final int DINING_STAY_MINUTES = 75;
    static final int CHECKIN_STAY_MINUTES = 45;
    static final int WALK_STAY_MINUTES = 40;

    private final ItinerarySessionService sessionService;
    private final ItineraryRefineIntentParser intentParser;
    private final SerperPlacesService serperPlacesService;
    private final ItineraryTransitPlanner itineraryTransitPlanner;
    private final ItineraryParkingAttacher itineraryParkingAttacher;
    private final ItineraryOpeningHoursChecker itineraryOpeningHoursChecker;
    private final ItineraryCandidateAttacher itineraryCandidateAttacher;
    private final ItineraryTemporalPlanner itineraryTemporalPlanner;
    private final ItinerarySafetyGuardrail itinerarySafetyGuardrail;
    private final ItineraryTimelineNormalizer itineraryTimelineNormalizer;

    public ItineraryRefineService(ItinerarySessionService sessionService,
                                  ItineraryRefineIntentParser intentParser,
                                  SerperPlacesService serperPlacesService,
                                  ItineraryTransitPlanner itineraryTransitPlanner,
                                  ItineraryParkingAttacher itineraryParkingAttacher,
                                  ItineraryOpeningHoursChecker itineraryOpeningHoursChecker,
                                  ItineraryCandidateAttacher itineraryCandidateAttacher,
                                  ItineraryTemporalPlanner itineraryTemporalPlanner,
                                  ItinerarySafetyGuardrail itinerarySafetyGuardrail,
                                  ItineraryTimelineNormalizer itineraryTimelineNormalizer) {
        this.sessionService = sessionService;
        this.intentParser = intentParser;
        this.serperPlacesService = serperPlacesService;
        this.itineraryTransitPlanner = itineraryTransitPlanner;
        this.itineraryParkingAttacher = itineraryParkingAttacher;
        this.itineraryOpeningHoursChecker = itineraryOpeningHoursChecker;
        this.itineraryCandidateAttacher = itineraryCandidateAttacher;
        this.itineraryTemporalPlanner = itineraryTemporalPlanner;
        this.itinerarySafetyGuardrail = itinerarySafetyGuardrail;
        this.itineraryTimelineNormalizer = itineraryTimelineNormalizer;
    }

    public ItineraryRefineResponse refine(String sessionId, String message, String lang) {
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        ItineraryPlan plan = sessionService.require(sessionId);
        LanguageEnum language = LanguageEnum.fromCode(lang);
        List<ItineraryNode> schedule = mutableSchedule(plan);
        if (schedule == null || schedule.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "当前行程没有可修改的节点");
        }

        ItineraryRefineIntent intent = intentParser.parse(message.trim(), plan, language);
        ActionType action = intent.resolvedAction();
        if (action == ActionType.DELETE) {
            return deleteStop(sessionId, plan, schedule, intent, language);
        }
        if (action == ActionType.INSERT_BEFORE) {
            return insertStop(sessionId, plan, schedule, intent, language, true);
        }
        if (action == ActionType.APPEND) {
            return insertStop(sessionId, plan, schedule, intent, language, false);
        }
        return replaceStop(sessionId, plan, schedule, intent, language);
    }

    private ItineraryRefineResponse replaceStop(String sessionId,
                                                ItineraryPlan plan,
                                                List<ItineraryNode> schedule,
                                                ItineraryRefineIntent intent,
                                                LanguageEnum language) {
        int index = clampIndex(intent.getTargetIndex(), schedule.size());
        ItineraryNode target = schedule.get(index);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "目标节点不存在");
        }

        String suburb = resolveSuburb(schedule, index);
        String searchQuery = resolveSearchQuery(intent.getSearchQuery(), suburb);
        SpotCategory category = SpotCategory.from(intent.getCategory());
        List<RealPlaceDto> places = searchPlaces(searchQuery, category);
        RealPlaceDto replacement = pickReplacement(places, schedule, index);
        if (replacement == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    language.text("附近没有找到符合条件的替代地点，请换个说法再试。",
                            "Could not find a nearby replacement. Try a more specific request."));
        }

        List<String> untouched = snapshotNames(schedule, index);
        bindVenue(target, replacement, language, category);
        target.setStayDuration(stayText(stayMinutesForNewStop(searchQuery, target.getCategory(), category), language));
        itineraryTransitPlanner.rememberPlaces(places);
        relinkFromStart(plan, places, language);
        itineraryCandidateAttacher.attachOne(plan, index, places, language);
        assertNeighborsUntouched(schedule, index, untouched);

        sessionService.put(sessionId, plan);
        String reason = firstFilled(intent.getReason(), defaultReplaceReason(replacement, language));
        log.info("refined session={} action=REPLACE index={} fromQuery={} to={}",
                sessionId, index, searchQuery, replacement.getTitle());
        return new ItineraryRefineResponse(plan, reason, index, searchQuery, ActionType.REPLACE);
    }

    private ItineraryRefineResponse insertStop(String sessionId,
                                               ItineraryPlan plan,
                                               List<ItineraryNode> schedule,
                                               ItineraryRefineIntent intent,
                                               LanguageEnum language,
                                               boolean beforeAnchor) {
        int anchorIndex = clampIndex(intent.getTargetIndex(), schedule.size());
        ItineraryNode anchor = schedule.get(anchorIndex);
        if (anchor == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "锚点不存在");
        }

        String suburb = resolveSuburb(schedule, anchorIndex);
        String searchQuery = resolveSearchQuery(intent.getSearchQuery(), suburb);
        SpotCategory category = SpotCategory.from(intent.getCategory());
        List<RealPlaceDto> places = searchPlaces(searchQuery, category);
        RealPlaceDto addition = pickNearbyPlace(places, schedule, anchorIndex, false);
        if (addition == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    language.text("附近没有找到符合条件的地点，请换个说法再试。",
                            "Could not find a nearby place to add. Try a more specific request."));
        }

        List<String> originalNames = snapshotAllNames(schedule);
        ItineraryNode extra = new ItineraryNode();
        extra.setNodeId("append-" + UUID.randomUUID().toString().substring(0, 8));
        bindVenue(extra, addition, language, category);
        extra.setStayDuration(stayText(stayMinutesForNewStop(searchQuery, extra.getCategory(), category), language));

        int insertIndex = beforeAnchor ? anchorIndex : anchorIndex + 1;
        // 站数不是拒绝条件：3/4/5 站都可以再追加，由时间线顺延与 22:00 跨度约束。
        schedule.add(insertIndex, extra);

        itineraryTransitPlanner.rememberPlaces(places);
        relinkFromStart(plan, places, language);
        itineraryCandidateAttacher.attachOne(plan, insertIndex, places, language);
        assertAppendPreserved(schedule, Math.max(0, insertIndex - 1), originalNames);

        sessionService.put(sessionId, plan);
        ActionType action = beforeAnchor ? ActionType.INSERT_BEFORE : ActionType.APPEND;
        String reason = beforeAnchor
                ? insertBeforeReason(anchor, addition, language)
                : appendReason(anchor, addition, language);
        log.info("refined session={} action={} after={} insert={} fromQuery={} to={}",
                sessionId, action, anchorIndex, insertIndex, searchQuery, addition.getTitle());
        return new ItineraryRefineResponse(plan, reason, insertIndex, searchQuery, action);
    }

    private ItineraryRefineResponse deleteStop(String sessionId,
                                               ItineraryPlan plan,
                                               List<ItineraryNode> schedule,
                                               ItineraryRefineIntent intent,
                                               LanguageEnum language) {
        int index = clampIndex(intent.getTargetIndex(), schedule.size());
        if (schedule.size() <= 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "行程至少需要保留一站");
        }
        ItineraryNode removed = schedule.remove(index);
        relinkFromStart(plan, List.of(), language);
        sessionService.put(sessionId, plan);
        String name = removed == null || removed.getPoiName() == null ? "" : removed.getPoiName();
        String reason = firstFilled(intent.getReason(), language.text(
                name.isBlank() ? "已删除对应行程卡片" : "已删除「" + name + "」",
                name.isBlank() ? "Removed that stop." : "Removed " + name + "."));
        return new ItineraryRefineResponse(plan, reason, Math.min(index, schedule.size() - 1),
                intent.getSearchQuery(), ActionType.DELETE);
    }

    private void relinkFromStart(ItineraryPlan plan, List<RealPlaceDto> places, LanguageEnum language) {
        itineraryTransitPlanner.apply(plan, language);
        itineraryParkingAttacher.attach(plan, language);
        itineraryTemporalPlanner.apply(plan, language);
        itineraryTimelineNormalizer.rechainFromStart(plan, language);
        itineraryOpeningHoursChecker.applyStatus(plan, places == null ? List.of() : places, language);
        itinerarySafetyGuardrail.apply(plan, language);
    }

    private static String insertBeforeReason(ItineraryNode anchor, RealPlaceDto place, LanguageEnum language) {
        String prevName = anchor == null || anchor.getPoiName() == null ? "" : anchor.getPoiName().trim();
        String newName = place == null || place.getTitle() == null ? "" : place.getTitle().trim();
        if (language.isZh()) {
            if (prevName.isBlank()) {
                return "已为您插入「" + newName + "」";
            }
            return "已为您在「" + prevName + "」前插入了「" + newName + "」";
        }
        if (prevName.isBlank()) {
            return "Inserted " + newName + ".";
        }
        return "Inserted " + newName + " before " + prevName + ".";
    }

    static String resolveSearchQuery(String raw, String suburb) {
        String query = raw == null || raw.isBlank() ? "highly rated place near {suburb}" : raw.trim();
        String place = suburb == null || suburb.isBlank() ? "Sydney" : suburb.trim();
        query = query.replace("{suburb}", place).replace("{Suburb}", place);
        String lower = query.toLowerCase(Locale.ROOT);
        if (!lower.contains(place.toLowerCase(Locale.ROOT)) && !lower.contains("sydney")) {
            query = query + " " + place + " Sydney";
        }
        return query.replaceAll("\\s+", " ").trim();
    }

    static String resolveSuburb(List<ItineraryNode> schedule, int index) {
        ItineraryNode node = schedule.get(index);
        String fromNode = SydneyPlaceLocator.guessSuburb(node == null ? null : node.getPoiName());
        if (!"Sydney".equals(fromNode)) {
            return fromNode;
        }
        if (index > 0 && schedule.get(index - 1) != null) {
            String previous = SydneyPlaceLocator.guessSuburb(schedule.get(index - 1).getPoiName());
            if (!"Sydney".equals(previous)) {
                return previous;
            }
        }
        if (index + 1 < schedule.size() && schedule.get(index + 1) != null) {
            String next = SydneyPlaceLocator.guessSuburb(schedule.get(index + 1).getPoiName());
            if (!"Sydney".equals(next)) {
                return next;
            }
        }
        return fromNode;
    }

    static RealPlaceDto pickReplacement(List<RealPlaceDto> places, List<ItineraryNode> schedule, int index) {
        return pickNearbyPlace(places, schedule, index, true);
    }

    /**
     * @param replacingOrigin true 时允许与 origin 同名以外的店替换 origin；false 时现有所有店名都占用。
     */
    static RealPlaceDto pickNearbyPlace(List<RealPlaceDto> places, List<ItineraryNode> schedule,
                                        int originIndex, boolean replacingOrigin) {
        if (places == null || places.isEmpty() || schedule == null
                || originIndex < 0 || originIndex >= schedule.size()) {
            return null;
        }
        ItineraryNode origin = schedule.get(originIndex);
        Set<String> occupied = occupiedKeys(schedule, replacingOrigin ? originIndex : -1);
        String originKey = key(origin == null ? null : origin.getPoiName());
        Double originLat = origin == null ? null : origin.getLatitude();
        Double originLng = origin == null ? null : origin.getLongitude();
        if ((originLat == null || originLng == null) && originIndex > 0 && schedule.get(originIndex - 1) != null) {
            originLat = schedule.get(originIndex - 1).getLatitude();
            originLng = schedule.get(originIndex - 1).getLongitude();
        }
        Double lat = originLat;
        Double lng = originLng;
        return places.stream()
                .filter(place -> place != null && place.getTitle() != null && !place.getTitle().isBlank())
                .filter(place -> place.getLatitude() != null && place.getLongitude() != null
                        && GeoDistanceUtils.isValidCoord(place.getLatitude(), place.getLongitude()))
                .filter(place -> {
                    String placeKey = key(place.getTitle());
                    return !placeKey.equals(originKey) && !occupied.contains(placeKey);
                })
                .min(Comparator
                        .comparingInt((RealPlaceDto place) -> distanceBucket(place, lat, lng))
                        .thenComparing(RealPlaceDto::getRating, Comparator.nullsLast(Comparator.reverseOrder())))
                .orElse(null);
    }

    static void bindVenue(ItineraryNode node, RealPlaceDto place, LanguageEnum language) {
        bindVenue(node, place, language, SpotCategory.fromText(
                firstFilled(place.getCategory(), node.getCategory())));
    }

    static void bindVenue(ItineraryNode node, RealPlaceDto place, LanguageEnum language, SpotCategory category) {
        String name = place.getTitle().trim();
        node.setPoiName(name);
        node.setLatitude(place.getLatitude());
        node.setLongitude(place.getLongitude());
        SpotCategory resolved = category == null || category == SpotCategory.OTHER
                ? SpotCategory.fromText(firstFilled(place.getCategory(), name))
                : category;
        node.setSpotCategory(resolved.name());
        node.setCategory(resolved.label(language));
        node.setCategoryZh(resolved.labelZh());
        node.setCategoryEn(resolved.labelEn());
        node.setSearchKeyword(name + " 拍照 机位 避坑");
        node.setRedditKeyword(name + " Sydney review");
        node.setWebsiteUrl("https://www.google.com/search?btnI=1&q="
                + URLEncoder.encode(name + " official website Sydney", StandardCharsets.UTF_8).replace("+", "%20"));
        node.setParkingSearchKeyword(null);
        node.setParkingTips(null);
        node.setSunsetCandidates(null);
        node.setOpenStatus(null);
        node.setStatusWarning(null);
        node.setOpeningHoursText(place.getOpeningHoursText());
        node.setDrivingForbidden(null);
        node.setAlcoholInvolved(resolved.alcohol() ? Boolean.TRUE : Boolean.FALSE);
        node.setSafetyWarning(null);
    }

    static String categoryZh(String category) {
        if (category == null || category.isBlank()) {
            return category;
        }
        String lower = category.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "japanese", "sushi", "ramen", "izakaya", "yakiniku")) {
            return containsAny(lower, "bbq", "yakiniku") ? "日料烧肉" : "日料";
        }
        if (containsAny(lower, "korean", "bbq", "barbecue")) {
            return "烤肉";
        }
        if (containsAny(lower, "cafe", "coffee")) {
            return "咖啡馆";
        }
        if (containsAny(lower, "brunch", "breakfast")) {
            return "早午餐";
        }
        if (containsAny(lower, "tea")) {
            return "下午茶";
        }
        if (containsAny(lower, "gallery", "museum", "art")) {
            return "艺廊";
        }
        if (containsAny(lower, "walk", "stroll", "promenade")) {
            return "散步 / 观景";
        }
        if (containsAny(lower, "bar", "pub", "brewery", "nightclub")) {
            return "酒吧";
        }
        if (containsAny(lower, "park", "lookout", "beach")) {
            return "观景";
        }
        if (containsAny(lower, "restaurant", "dining")) {
            return "餐厅";
        }
        return category.trim();
    }

    private static int distanceBucket(RealPlaceDto place, Double lat, Double lng) {
        if (lat == null || lng == null
                || !GeoDistanceUtils.isValidCoord(lat, lng)
                || !GeoDistanceUtils.isValidCoord(place.getLatitude(), place.getLongitude())) {
            return 1;
        }
        double km = GeoDistanceUtils.calculateDistanceKm(lat, lng, place.getLatitude(), place.getLongitude());
        return km <= PREFER_NEAR_KM ? 0 : 1;
    }

    private static Set<String> occupiedKeys(List<ItineraryNode> schedule, int skipIndex) {
        Set<String> occupied = new HashSet<>();
        for (int i = 0; i < schedule.size(); i++) {
            if (i == skipIndex || schedule.get(i) == null) {
                continue;
            }
            String key = key(schedule.get(i).getPoiName());
            if (!key.isBlank()) {
                occupied.add(key);
            }
        }
        return occupied;
    }

    private static List<ItineraryNode> mutableSchedule(ItineraryPlan plan) {
        List<ItineraryNode> schedule = plan.getSchedule();
        if (schedule == null) {
            return null;
        }
        if (!(schedule instanceof ArrayList)) {
            schedule = new ArrayList<>(schedule);
            plan.setSchedule(schedule);
        }
        return schedule;
    }

    private static void bindAppendArrival(ItineraryNode previous, ItineraryNode extra, ItineraryPlan plan) {
        if (previous == null || extra == null) {
            return;
        }
        LocalTime prevArrival = ItineraryOpeningHoursChecker.parseClock(
                firstFilled(previous.getArrivalTime(), previous.getEstimatedArrival()));
        if (prevArrival == null) {
            return;
        }
        int hop = Math.max(ItineraryOpeningHoursChecker.resolveTransitMinutes(previous), 1);
        LocalTime leave = ItineraryTemporalPlanner.leaveTime(previous, plan.getSunsetEvaluation());
        if (leave == null) {
            leave = prevArrival.plusMinutes(ItineraryOpeningHoursChecker.resolveStayMinutes(previous));
        }
        LocalTime arrival = leave.plusMinutes(hop);
        LocalTime diningFloor = ItineraryTemporalPlanner.earliestDiningArrival(plan.getSunsetEvaluation(), hop);
        if (diningFloor != null && ItineraryTemporalPlanner.isLongDining(extra) && arrival.isBefore(diningFloor)) {
            arrival = diningFloor;
        }
        String clock = OpeningHoursParser.formatClock(arrival);
        extra.setArrivalTime(clock);
        extra.setEstimatedArrival(clock);
        if (previous.getLatitude() != null && previous.getLongitude() != null
                && extra.getLatitude() != null && extra.getLongitude() != null
                && GeoDistanceUtils.isValidCoord(previous.getLatitude(), previous.getLongitude())
                && GeoDistanceUtils.isValidCoord(extra.getLatitude(), extra.getLongitude())) {
            double km = GeoDistanceUtils.calculateDistanceKm(
                    previous.getLatitude(), previous.getLongitude(),
                    extra.getLatitude(), extra.getLongitude());
            log.info("append hop. from={} to={} km={} transitMin={} arrival={}",
                    previous.getPoiName(), extra.getPoiName(),
                    String.format(Locale.ROOT, "%.2f", km), hop, clock);
        }
    }

    static int stayMinutesForNewStop(String searchQuery, String category) {
        return stayMinutesForNewStop(searchQuery, category, SpotCategory.fromText(searchQuery + " " + category));
    }

    static int stayMinutesForNewStop(String searchQuery, String category, SpotCategory spotCategory) {
        SpotCategory resolved = spotCategory == null || spotCategory == SpotCategory.OTHER
                ? SpotCategory.fromText(nullToEmpty(searchQuery) + " " + nullToEmpty(category))
                : spotCategory;
        if (resolved != SpotCategory.OTHER) {
            return resolved.stayMinutes();
        }
        String blob = (nullToEmpty(searchQuery) + " " + nullToEmpty(category)).toLowerCase(Locale.ROOT);
        if (SpotCategory.looksLikeWalk(blob)) {
            return WALK_STAY_MINUTES;
        }
        if (SpotCategory.looksLikeDining(blob)) {
            return DINING_STAY_MINUTES;
        }
        return CHECKIN_STAY_MINUTES;
    }

    private List<RealPlaceDto> searchPlaces(String searchQuery, SpotCategory category) {
        SpotCategory resolved = category == null ? SpotCategory.OTHER : category;
        List<RealPlaceDto> places = resolved.usesScenicSearch()
                ? serperPlacesService.searchScenicPlaces(searchQuery)
                : serperPlacesService.searchVerifiedPlaces(searchQuery);
        if (resolved.scenicWalk() || resolved == SpotCategory.ATTRACTION) {
            List<RealPlaceDto> filtered = places.stream()
                    .filter(place -> place != null && !SpotCategory.looksLikeDiningPlace(place.getTitle(), place.getCategory()))
                    .toList();
            if (!filtered.isEmpty()) {
                return filtered;
            }
        }
        return places;
    }

    static String stayText(int minutes, LanguageEnum language) {
        return language.isZh() ? "停留" + minutes + "分钟" : minutes + " min stay";
    }

    private static String appendReason(ItineraryNode previous, RealPlaceDto place, LanguageEnum language) {
        String prevName = previous == null || previous.getPoiName() == null ? "" : previous.getPoiName().trim();
        String newName = place == null || place.getTitle() == null ? "" : place.getTitle().trim();
        if (language.isZh()) {
            if (prevName.isBlank() && newName.isBlank()) {
                return "已为您追加新的一站";
            }
            if (prevName.isBlank()) {
                return "已为您追加了" + newName;
            }
            return "已为您在「" + prevName + "」后追加了「" + newName + "」";
        }
        if (prevName.isBlank() && newName.isBlank()) {
            return "Added a new stop to your itinerary.";
        }
        if (prevName.isBlank()) {
            return "Added " + newName + " to your itinerary.";
        }
        return "Added " + newName + " after " + prevName + ".";
    }

    private static List<String> snapshotAllNames(List<ItineraryNode> schedule) {
        List<String> names = new ArrayList<>();
        for (ItineraryNode node : schedule) {
            names.add(node == null ? null : node.getPoiName());
        }
        return names;
    }

    private static void assertAppendPreserved(List<ItineraryNode> schedule, int afterIndex, List<String> original) {
        for (int i = 0; i < original.size(); i++) {
            int actualIndex = i <= afterIndex ? i : i + 1;
            if (actualIndex >= schedule.size()) {
                log.warn("append shifted original stop out of range. originalIndex={}", i);
                continue;
            }
            String expected = original.get(i);
            String actual = schedule.get(actualIndex) == null ? null : schedule.get(actualIndex).getPoiName();
            if (expected != null && !expected.equals(actual)) {
                log.warn("original poi changed during append. originalIndex={} expected={} actual={}",
                        i, expected, actual);
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<String> snapshotNames(List<ItineraryNode> schedule, int index) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < schedule.size(); i++) {
            if (i == index) {
                names.add(null);
                continue;
            }
            ItineraryNode node = schedule.get(i);
            names.add(node == null ? null : node.getPoiName());
        }
        return names;
    }

    private static void assertNeighborsUntouched(List<ItineraryNode> schedule, int index, List<String> untouched) {
        for (int i = 0; i < schedule.size(); i++) {
            if (i == index) {
                continue;
            }
            String expected = untouched.get(i);
            String actual = schedule.get(i) == null ? null : schedule.get(i).getPoiName();
            if (expected != null && !expected.equals(actual)) {
                log.warn("neighbor poi changed during refine. index={} expected={} actual={}", i, expected, actual);
            }
        }
    }

    private static int clampIndex(Integer targetIndex, int size) {
        if (targetIndex == null) {
            return Math.max(0, size - 1);
        }
        if (targetIndex < 0 || targetIndex >= size) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "无法定位要替换的行程卡片");
        }
        return targetIndex;
    }

    private static String defaultReplaceReason(RealPlaceDto place, LanguageEnum language) {
        String name = place.getTitle();
        return language.text("已为您替换为附近的 " + name, "Replaced this stop with nearby " + name);
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String firstFilled(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback;
    }
}
