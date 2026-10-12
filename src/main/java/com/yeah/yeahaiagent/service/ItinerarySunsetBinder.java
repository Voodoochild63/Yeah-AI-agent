package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.dto.SpotCandidateDto;
import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import com.yeah.yeahaiagent.model.dto.TransitOptions;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.util.GeoDistanceUtils;
import com.yeah.yeahaiagent.util.OpeningHoursParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 落日后处理：16:30 时域门禁 + 就近 5km Serper 检索，禁止把天文台山塞进下午半日游。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ItinerarySunsetBinder {

    static final LocalTime SUNSET_GATE = LocalTime.of(16, 30);
    static final double MAX_STRAIGHT_KM = 5.0d;
    static final int MAX_SUNSET_CANDIDATES = 3;
    static final double MIN_RATING = 4.0d;

    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern SUNSET_INTENT = Pattern.compile(
            "落日|日落|晚霞|黄昏|看日落|追日|golden\\s*hour|\\bsunset\\b|\\bdusk\\b|\\bsundown\\b",
            Pattern.CASE_INSENSITIVE);

    private final SerperPlacesService serperPlacesService;
    private final SydneyPlaceLocator placeLocator;

    public static boolean wantsSunset(String message) {
        return message != null && SUNSET_INTENT.matcher(message).find();
    }

    public void apply(ItineraryPlan plan, SunsetEvaluation weather, String userMessage, LanguageEnum language) {
        if (plan == null) {
            return;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        LocalTime lastEnd = lastDeparture(plan);
        stripAfternoonSunsetNodes(plan, lastEnd, lang);
        lastEnd = lastDeparture(plan);

        boolean userAsked = wantsSunset(userMessage);
        boolean inWindow = lastEnd != null && !lastEnd.isBefore(SUNSET_GATE);
        if (!inWindow && !userAsked) {
            log.info("sunset card skipped. lastDeparture={} userAsked={}", lastEnd, userAsked);
            plan.setSunsetEvaluation(null);
            return;
        }
        if (weather == null || !weather.recommended()) {
            plan.setSunsetEvaluation(withoutDistantSpot(weather));
            return;
        }
        List<RealPlaceDto> nearby = searchNearbySunsetSpots(plan, weather);
        if (nearby.isEmpty()) {
            log.info("sunset weather ok but no venue within {}km of last stop", MAX_STRAIGHT_KM);
            plan.setSunsetEvaluation(new SunsetEvaluation(
                    true,
                    weather.sunsetTime(),
                    weather.goldenHourWindow(),
                    weather.cloudCover(),
                    weather.rainProb(),
                    weather.recommendationReason(),
                    null,
                    lang.text("在当前街区找一处朝西的海边或高点即可，不要跨区赶去市中心。",
                            "Stay in this suburb — pick a west-facing headland or high point. Do not cross town.")));
            return;
        }
        ItineraryNode finale = ensureSunsetNode(plan, nearby, lang, userMessage, weather);
        RealPlaceDto chosen = nearby.get(0);
        log.info("sunset spot bound. name={} lastDeparture={} nodes={}",
                chosen.getTitle(), lastEnd, plan.getSchedule() == null ? 0 : plan.getSchedule().size());
        plan.setSunsetEvaluation(new SunsetEvaluation(
                true,
                weather.sunsetTime(),
                weather.goldenHourWindow(),
                weather.cloudCover(),
                weather.rainProb(),
                weather.recommendationReason(),
                finale == null ? chosen.getTitle() : finale.getPoiName(),
                nearbyPhotoTip(chosen, lang)));
    }

    static LocalTime lastDeparture(ItineraryPlan plan) {
        ItineraryNode last = lastNode(plan);
        if (last == null) {
            return null;
        }
        LocalTime arrival = parseClock(firstFilled(last.getEstimatedArrival(), last.getArrivalTime()));
        if (arrival == null) {
            return null;
        }
        return arrival.plusMinutes(ItineraryOpeningHoursChecker.resolveStayMinutes(last));
    }

    static List<RealPlaceDto> withinMaxKm(List<RealPlaceDto> places, double lat, double lng) {
        if (places == null || places.isEmpty() || !GeoDistanceUtils.isValidCoord(lat, lng)) {
            return List.of();
        }
        List<RealPlaceDto> kept = new ArrayList<>();
        for (RealPlaceDto place : places) {
            if (place == null || place.getLatitude() == null || place.getLongitude() == null) {
                continue;
            }
            if (!GeoDistanceUtils.isValidCoord(place.getLatitude(), place.getLongitude())) {
                continue;
            }
            if (place.getRating() != null && place.getRating() < MIN_RATING) {
                continue;
            }
            double km = GeoDistanceUtils.calculateDistanceKm(lat, lng, place.getLatitude(), place.getLongitude());
            if (km <= MAX_STRAIGHT_KM) {
                kept.add(place);
            } else {
                log.info("sunset candidate dropped, too far. name={} km={}", place.getTitle(),
                        String.format(Locale.ROOT, "%.2f", km));
            }
        }
        kept.sort(Comparator.comparingDouble((RealPlaceDto place) ->
                GeoDistanceUtils.calculateDistanceKm(lat, lng, place.getLatitude(), place.getLongitude())));
        return kept;
    }

    List<RealPlaceDto> searchScenic(String query) {
        if (serperPlacesService == null || query == null || query.isBlank()) {
            return List.of();
        }
        return serperPlacesService.searchScenicPlaces(query);
    }

    private List<RealPlaceDto> searchNearbySunsetSpots(ItineraryPlan plan, SunsetEvaluation weather) {
        ItineraryNode anchor = sunsetAnchor(plan, weather);
        if (anchor == null) {
            return List.of();
        }
        double[] origin = originCoord(plan, anchor);
        if (origin == null) {
            return List.of();
        }
        String suburb = resolveSuburb(plan, anchor);
        String query = scenicQuery(suburb, origin);
        log.info("sunset serper query={}", query);
        List<RealPlaceDto> merged = new ArrayList<>(withinMaxKm(searchScenic(query), origin[0], origin[1]));
        for (RealPlaceDto local : withinMaxKm(localLookouts(), origin[0], origin[1])) {
            if (merged.stream().noneMatch(existing -> sameTitle(existing, local))) {
                merged.add(local);
            }
        }
        merged.sort(Comparator.comparingDouble((RealPlaceDto place) ->
                GeoDistanceUtils.calculateDistanceKm(origin[0], origin[1], place.getLatitude(), place.getLongitude())));
        if (merged.size() > MAX_SUNSET_CANDIDATES) {
            return List.copyOf(merged.subList(0, MAX_SUNSET_CANDIDATES));
        }
        return List.copyOf(merged);
    }

    /**
     * 落日检索原点：黄金窗内的正餐之前那一站，避免围着 17:31 的餐厅去搜市中心机位。
     */
    static ItineraryNode sunsetAnchor(ItineraryPlan plan, SunsetEvaluation weather) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return null;
        }
        int dining = ItineraryTemporalPlanner.findDiningOverlappingLock(schedule, weather);
        if (dining > 0) {
            return schedule.get(dining - 1);
        }
        return lastNode(plan);
    }

    static String scenicQuery(String suburb, double[] origin) {
        if (suburb == null || suburb.isBlank() || "Sydney".equalsIgnoreCase(suburb)) {
            return "sunset photography spot lookout near " + origin[0] + "," + origin[1] + " Sydney";
        }
        return "sunset photography spot lookout near " + suburb + " Sydney";
    }

    private ItineraryNode ensureSunsetNode(ItineraryPlan plan, List<RealPlaceDto> nearby, LanguageEnum language,
                                          String userMessage, SunsetEvaluation weather) {
        List<ItineraryNode> schedule = plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return null;
        }
        boolean fullDay = ItineraryDayScope.isFullDay(userMessage);
        int existing = ItineraryTemporalPlanner.indexOfSunset(schedule);
        if (existing >= 0 && isUsableSunsetSlot(plan, schedule.get(existing))) {
            fillSunsetNode(schedule.get(existing), nearby, language);
            markEndIfNeeded(schedule.get(existing), existing, schedule.size(), fullDay, language);
            return schedule.get(existing);
        }

        int diningOverlap = ItineraryTemporalPlanner.findDiningOverlappingLock(schedule, weather);
        int insertAt = diningOverlap >= 0 ? diningOverlap : schedule.size();
        ItineraryNode last = lastNode(plan);
        if (diningOverlap < 0 && last != null && isUsableSunsetFinale(plan, last)) {
            fillSunsetNode(last, nearby, language);
            markEndIfNeeded(last, schedule.size() - 1, schedule.size(), fullDay, language);
            return last;
        }
        ItineraryNode sunset = new ItineraryNode();
        fillSunsetNode(sunset, nearby, language);
        sunset.setNodeId("sunset-finale");
        ItineraryNode previous = insertAt <= 0 ? last : schedule.get(Math.max(0, insertAt - 1));
        bindLastHop(previous, sunset, language);
        LocalTime depart = previous == last ? lastDeparture(plan) : departureOf(previous);
        int hop = ItineraryOpeningHoursChecker.resolveTransitMinutes(previous);
        LocalTime arrival = (depart == null ? LocalTime.of(17, 5) : depart.plusMinutes(Math.max(hop, 1)));
        ItineraryTemporalPlanner.SunsetWindow window = ItineraryTemporalPlanner.resolveWindow(weather);
        if (window != null && arrival.isAfter(window.lockStart()) && diningOverlap >= 0) {
            arrival = window.lockStart();
        }
        String clock = OpeningHoursParser.formatClock(arrival);
        sunset.setArrivalTime(clock);
        sunset.setEstimatedArrival(clock);
        if (insertAt >= schedule.size()) {
            markEndIfNeeded(sunset, insertAt, insertAt + 1, fullDay, language);
            schedule.add(sunset);
        } else {
            schedule.add(insertAt, sunset);
            bindLastHop(sunset, schedule.get(insertAt + 1), language);
        }
        return sunset;
    }

    private static void markEndIfNeeded(ItineraryNode node, int index, int size, boolean fullDay,
                                        LanguageEnum language) {
        if (node == null) {
            return;
        }
        if (!fullDay && index == size - 1) {
            node.setTransitToNext(language.text("行程结束", "End of itinerary"));
            node.setTransitOptions(null);
        }
    }

    private static LocalTime departureOf(ItineraryNode node) {
        LocalTime arrival = parseClock(firstFilled(node.getEstimatedArrival(), node.getArrivalTime()));
        if (arrival == null) {
            return null;
        }
        return arrival.plusMinutes(ItineraryOpeningHoursChecker.resolveStayMinutes(node));
    }

    private boolean isUsableSunsetSlot(ItineraryPlan plan, ItineraryNode node) {
        if (!isSunsetViewpoint(node) || isObservatoryHill(node.getPoiName())) {
            return false;
        }
        return !shouldDropSunsetStop(plan, node);
    }

    private boolean isUsableSunsetFinale(ItineraryPlan plan, ItineraryNode last) {
        if (!isSunsetViewpoint(last) || isObservatoryHill(last.getPoiName())) {
            return false;
        }
        return !shouldDropSunsetStop(plan, last);
    }

    private void fillSunsetNode(ItineraryNode node, List<RealPlaceDto> nearby, LanguageEnum language) {
        RealPlaceDto best = nearby.get(0);
        node.setPoiName(best.getTitle());
        node.setCategory(language.isZh() ? "落日观景" : "Sunset lookout");
        node.setCategoryZh("落日观景");
        node.setCategoryEn("Sunset lookout");
        node.setSpotCategory("SUNSET");
        node.setStayDuration(language.isZh() ? "45分钟" : "45 min");
        node.setLatitude(best.getLatitude());
        node.setLongitude(best.getLongitude());
        node.setOpeningHoursText(language.isZh() ? "全天开放" : "Open all day");
        List<SpotCandidateDto> slots = toSunsetCandidates(nearby);
        node.setCandidates(slots);
        node.setSunsetCandidates(slots);
        node.setSearchKeyword(best.getTitle() + " 拍照 机位 避坑");
        node.setRedditKeyword(best.getTitle() + " Sydney sunset lookout");
        String suburb = SydneyPlaceLocator.guessSuburb(best.getTitle() + " " + nullToEmpty(best.getAddress()));
        String keyword = "Sydney".equals(suburb)
                ? "parking near " + best.getTitle()
                : "parking near " + best.getTitle() + " " + suburb;
        node.setParkingSearchKeyword(keyword);
    }

    private static List<SpotCandidateDto> toSunsetCandidates(List<RealPlaceDto> nearby) {
        List<SpotCandidateDto> slots = new ArrayList<>();
        for (RealPlaceDto place : nearby) {
            if (place == null || place.getTitle() == null || place.getTitle().isBlank()) {
                continue;
            }
            slots.add(new SpotCandidateDto(
                    place.getTitle().trim(),
                    firstFilled(place.getCategory(), "Sunset lookout"),
                    place.getRating(),
                    place.getRatingCount(),
                    place.getLatitude(),
                    place.getLongitude(),
                    place.getOpeningHoursText(),
                    place.getAddress(),
                    "golden-hour views"));
            if (slots.size() >= MAX_SUNSET_CANDIDATES) {
                break;
            }
        }
        return slots;
    }

    private void bindLastHop(ItineraryNode from, ItineraryNode to, LanguageEnum language) {
        if (from == null || to == null
                || from.getLatitude() == null || from.getLongitude() == null
                || to.getLatitude() == null || to.getLongitude() == null
                || !GeoDistanceUtils.isValidCoord(from.getLatitude(), from.getLongitude())
                || !GeoDistanceUtils.isValidCoord(to.getLatitude(), to.getLongitude())) {
            return;
        }
        double km = GeoDistanceUtils.calculateDistanceKm(
                from.getLatitude(), from.getLongitude(), to.getLatitude(), to.getLongitude());
        TransitOptions options = ItineraryTransitPlanner.estimateOptions(km, language);
        String mode = km <= ItineraryTransitPlanner.WALK_MAX_KM
                ? ItineraryTransitPlanner.MODE_WALKING
                : ItineraryTransitPlanner.MODE_DRIVING;
        from.setTransitOptions(options);
        from.setSelectedMode(mode);
        from.setTransitToNext(ItineraryTransitPlanner.formatSelected(options, mode, language));
    }

    private static boolean sameTitle(RealPlaceDto left, RealPlaceDto right) {
        if (left == null || right == null || left.getTitle() == null || right.getTitle() == null) {
            return false;
        }
        return left.getTitle().trim().equalsIgnoreCase(right.getTitle().trim());
    }

    private void stripAfternoonSunsetNodes(ItineraryPlan plan, LocalTime lastEnd, LanguageEnum language) {
        List<ItineraryNode> schedule = plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return;
        }
        if (lastEnd != null && !lastEnd.isBefore(SUNSET_GATE)) {
            return;
        }
        ItineraryNode last = lastNode(plan);
        if (last == null) {
            return;
        }
        retagSunsetLabels(last, language);
        if (!shouldDropSunsetStop(plan, last)) {
            return;
        }
        schedule.remove(schedule.size() - 1);
        ItineraryNode newLast = lastNode(plan);
        if (newLast != null) {
            newLast.setTransitToNext(language.text("行程结束", "End of itinerary"));
            newLast.setTransitOptions(null);
            newLast.setSelectedMode(null);
        }
        log.info("dropped forced sunset stop. removed={}", last.getPoiName());
    }

    private boolean shouldDropSunsetStop(ItineraryPlan plan, ItineraryNode last) {
        if (!isSunsetViewpoint(last)) {
            return false;
        }
        List<ItineraryNode> schedule = plan.getSchedule();
        if (schedule == null || schedule.size() < 2) {
            return false;
        }
        if (isObservatoryHill(last.getPoiName())) {
            return true;
        }
        ItineraryNode previous = previousNode(plan, last);
        if (previous == null) {
            return false;
        }
        double[] from = originCoord(plan, previous);
        double[] to = originCoord(plan, last);
        if (from != null && to != null) {
            double km = GeoDistanceUtils.calculateDistanceKm(from[0], from[1], to[0], to[1]);
            return km > MAX_STRAIGHT_KM;
        }
        return true;
    }

    private static void retagSunsetLabels(ItineraryNode node, LanguageEnum language) {
        if (node == null || !looksSunsetTagged(node)) {
            return;
        }
        boolean beach = blob(node).contains("beach") || blob(node).contains("海");
        String zh = beach ? "海滩散步" : "街区漫步";
        String en = beach ? "Beach stroll" : "Neighbourhood stroll";
        node.setCategory(language.isZh() ? zh : en);
        node.setCategoryZh(zh);
        node.setCategoryEn(en);
    }

    private static boolean isSunsetViewpoint(ItineraryNode node) {
        if (node == null) {
            return false;
        }
        if (isObservatoryHill(node.getPoiName())) {
            return true;
        }
        String text = blob(node);
        return text.contains("sunset")
                || text.contains("落日")
                || text.contains("日落")
                || text.contains("晚霞")
                || text.contains("golden hour")
                || (text.contains("lookout") && (text.contains("日落") || text.contains("sunset")));
    }

    private static boolean looksSunsetTagged(ItineraryNode node) {
        String tags = String.join(" ",
                nullToEmpty(node.getCategory()),
                nullToEmpty(node.getCategoryZh()),
                nullToEmpty(node.getCategoryEn())).toLowerCase(Locale.ROOT);
        return tags.contains("日落")
                || tags.contains("落日")
                || tags.contains("晚霞")
                || tags.contains("sunset")
                || tags.contains("golden");
    }

    private static boolean isObservatoryHill(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("observatory hill");
    }

    private String resolveSuburb(ItineraryPlan plan, ItineraryNode node) {
        String fromName = SydneyPlaceLocator.guessSuburb(nullToEmpty(node.getPoiName()));
        if (!"Sydney".equals(fromName)) {
            return fromName;
        }
        String fromKeyword = SydneyPlaceLocator.guessSuburb(nullToEmpty(node.getParkingSearchKeyword()));
        if (!"Sydney".equals(fromKeyword)) {
            return fromKeyword;
        }
        if (node.getCandidates() != null) {
            for (SpotCandidateDto candidate : node.getCandidates()) {
                if (candidate == null) {
                    continue;
                }
                String fromAddress = SydneyPlaceLocator.guessSuburb(
                        nullToEmpty(candidate.getAddress()) + " " + nullToEmpty(candidate.getName()));
                if (!"Sydney".equals(fromAddress)) {
                    return fromAddress;
                }
            }
        }
        ItineraryNode previous = previousNode(plan, node);
        if (previous != null) {
            String prev = SydneyPlaceLocator.guessSuburb(nullToEmpty(previous.getPoiName()));
            if (!"Sydney".equals(prev)) {
                return prev;
            }
        }
        return fromName;
    }

    private double[] originCoord(ItineraryPlan plan, ItineraryNode node) {
        if (node == null) {
            return null;
        }
        if (node.getLatitude() != null && node.getLongitude() != null
                && GeoDistanceUtils.isValidCoord(node.getLatitude(), node.getLongitude())) {
            return new double[]{node.getLatitude(), node.getLongitude()};
        }
        return placeLocator.locate(node.getPoiName())
                .map(point -> new double[]{point.lat(), point.lng()})
                .orElseGet(() -> {
                    ItineraryNode previous = previousNode(plan, node);
                    if (previous == null || previous == node) {
                        return null;
                    }
                    return originCoord(plan, previous);
                });
    }

    private static List<RealPlaceDto> localLookouts() {
        return List.of(
                lookout("Coogee Beach Headland", -33.9238, 151.2579),
                lookout("Gordons Bay Lookout", -33.9162, 151.2634),
                lookout("Mahon Pool", -33.9176, 151.2678),
                lookout("Centennial Park", -33.8947, 151.2328),
                lookout("Clovelly Beach", -33.9122, 151.2670),
                lookout("Bronte Beach", -33.9032, 151.2680),
                lookout("Bondi Icebergs Pool", -33.8947, 151.2743),
                lookout("Observatory Hill Park", -33.8593, 151.2048),
                lookout("Mrs Macquaries Chair", -33.8594, 151.2222)
        );
    }

    private static RealPlaceDto lookout(String title, double lat, double lng) {
        RealPlaceDto dto = new RealPlaceDto();
        dto.setTitle(title);
        dto.setLatitude(lat);
        dto.setLongitude(lng);
        dto.setCategory("Scenic spot");
        dto.setRating(4.5d);
        return dto;
    }

    private static String nearbyPhotoTip(RealPlaceDto spot, LanguageEnum language) {
        String name = spot.getTitle() == null ? "" : spot.getTitle();
        return language.text(
                "提前 20 分钟到 %s，朝西留一点海或树的前景，日落后再拍 10 分钟蓝调。".formatted(name),
                "Arrive 20 minutes early at %s. Keep a west-facing foreground, then wait 10 minutes after sunset for blue hour.".formatted(name));
    }

    private static SunsetEvaluation withoutDistantSpot(SunsetEvaluation weather) {
        if (weather == null) {
            return null;
        }
        return new SunsetEvaluation(
                weather.recommended(),
                weather.sunsetTime(),
                weather.goldenHourWindow(),
                weather.cloudCover(),
                weather.rainProb(),
                weather.recommendationReason(),
                null,
                weather.photoTip());
    }

    private static ItineraryNode lastNode(ItineraryPlan plan) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return null;
        }
        for (int i = schedule.size() - 1; i >= 0; i--) {
            if (schedule.get(i) != null) {
                return schedule.get(i);
            }
        }
        return null;
    }

    private static ItineraryNode previousNode(ItineraryPlan plan, ItineraryNode node) {
        List<ItineraryNode> schedule = plan.getSchedule();
        if (schedule == null) {
            return null;
        }
        int index = schedule.indexOf(node);
        if (index <= 0) {
            return null;
        }
        return schedule.get(index - 1);
    }

    private static LocalTime parseClock(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = CLOCK.matcher(raw.trim());
        if (!matcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    private static String blob(ItineraryNode node) {
        return String.join(" ",
                nullToEmpty(node.getPoiName()),
                nullToEmpty(node.getCategory()),
                nullToEmpty(node.getCategoryZh()),
                nullToEmpty(node.getCategoryEn())).toLowerCase(Locale.ROOT);
    }

    private static String firstFilled(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
