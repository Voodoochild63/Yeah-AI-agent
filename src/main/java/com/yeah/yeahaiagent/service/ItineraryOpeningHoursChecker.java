package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.dto.TransitModeOption;
import com.yeah.yeahaiagent.model.dto.TransitOptions;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.model.enums.OpenStatus;
import com.yeah.yeahaiagent.util.OpeningHoursParser;
import com.yeah.yeahaiagent.util.OpeningHoursParser.DayHours;
import com.yeah.yeahaiagent.util.OpeningHoursParser.Window;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用上一站停留 + 真实通勤分钟推到达时刻，再和当天营业时间做防撞。
 */
@Component
@Slf4j
public class ItineraryOpeningHoursChecker {

    static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    static final LocalTime DEFAULT_START = LocalTime.of(10, 0);
    static final int CLOSING_SOON_MINUTES = 30;
    static final int CAFE_STAY_MINUTES = 45;
    static final int PARK_STAY_MINUTES = 75;
    static final int DEFAULT_STAY_MINUTES = 60;

    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final Pattern STAY_HOURS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:个)?(?:小时|hours?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STAY_MINUTES = Pattern.compile("(\\d+)\\s*(?:分钟|mins?|minutes?)",
            Pattern.CASE_INSENSITIVE);

    private final Clock clock;

    public ItineraryOpeningHoursChecker() {
        this(Clock.system(SYDNEY));
    }

    ItineraryOpeningHoursChecker(Clock clock) {
        this.clock = clock == null ? Clock.system(SYDNEY) : clock;
    }

    public void apply(ItineraryPlan plan, List<RealPlaceDto> places, LanguageEnum language) {
        applyFrom(plan, 0, places, language);
    }

    /**
     * 只从 {@code fromIndex} 起重算到达时刻与营业状态，前面的节点保持原样。
     * 局部替换一张卡片后调用，避免整条时间轴被重写。
     */
    public void applyFrom(ItineraryPlan plan, int fromIndex, List<RealPlaceDto> places, LanguageEnum language) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return;
        }
        int start = Math.max(0, Math.min(fromIndex, schedule.size() - 1));
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        DayOfWeek day = clock.instant().atZone(SYDNEY).getDayOfWeek();
        LocalTime cursor = cursorAt(schedule, start);
        for (int i = start; i < schedule.size(); i++) {
            ItineraryNode node = schedule.get(i);
            if (node == null) {
                continue;
            }
            String arrivalText = OpeningHoursParser.formatClock(cursor);
            node.setEstimatedArrival(arrivalText);
            node.setArrivalTime(arrivalText);
            RealPlaceDto place = matchPlace(node.getPoiName(), places);
            List<String> hourLines = place == null || place.getOpeningHours() == null
                    ? List.of()
                    : place.getOpeningHours();
            DayHours hours = OpeningHoursParser.forDay(hourLines, day);
            if (!hours.known() && isAlwaysOpenVenue(node, place)) {
                hours = DayHours.open24();
            }
            Collision collision = evaluate(cursor, hours);
            node.setOpenStatus(collision.status().name());
            node.setOpensAt(collision.opensAt());
            node.setClosesAt(collision.closesAt());
            String hoursText = OpeningHoursParser.friendlyText(hours, hourLines, lang.isZh());
            if (hoursText == null && isAlwaysOpenVenue(node, place)) {
                hoursText = lang.text("常年开放", "Open year-round");
            }
            if (hoursText == null && place != null && !blank(place.getOpeningHoursText())) {
                hoursText = place.getOpeningHoursText();
            }
            node.setOpeningHoursText(hoursText);
            node.setStatusWarning(warning(collision, arrivalText, hoursText, lang));
            if (blank(node.getStayDuration())) {
                node.setStayDuration(defaultStayText(node, lang));
            }
            log.info("open-hours check. poi={} arrival={} status={} hours={}",
                    node.getPoiName(), arrivalText, collision.status(), hoursText);
            if (i < schedule.size() - 1) {
                cursor = OpeningHoursParser.plusMinutesClamped(
                        cursor, resolveStayMinutes(node) + resolveTransitMinutes(node));
            }
        }
    }

    /**
     * 只刷新开闭店状态，不改写已经顺推好的到达时刻。
     */
    public void applyStatus(ItineraryPlan plan, List<RealPlaceDto> places, LanguageEnum language) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        DayOfWeek day = clock.instant().atZone(SYDNEY).getDayOfWeek();
        for (ItineraryNode node : schedule) {
            if (node == null) {
                continue;
            }
            LocalTime arrival = parseClock(firstFilled(node.getArrivalTime(), node.getEstimatedArrival()));
            if (arrival == null) {
                continue;
            }
            String arrivalText = OpeningHoursParser.formatClock(arrival);
            RealPlaceDto place = matchPlace(node.getPoiName(), places);
            List<String> hourLines = place == null || place.getOpeningHours() == null
                    ? List.of()
                    : place.getOpeningHours();
            DayHours hours = OpeningHoursParser.forDay(hourLines, day);
            if (!hours.known() && isAlwaysOpenVenue(node, place)) {
                hours = DayHours.open24();
            }
            Collision collision = evaluate(arrival, hours);
            node.setOpenStatus(collision.status().name());
            node.setOpensAt(collision.opensAt());
            node.setClosesAt(collision.closesAt());
            String hoursText = OpeningHoursParser.friendlyText(hours, hourLines, lang.isZh());
            if (hoursText == null && isAlwaysOpenVenue(node, place)) {
                hoursText = lang.text("常年开放", "Open year-round");
            }
            if (hoursText == null && place != null && !blank(place.getOpeningHoursText())) {
                hoursText = place.getOpeningHoursText();
            }
            node.setOpeningHoursText(hoursText);
            node.setStatusWarning(warning(collision, arrivalText, hoursText, lang));
        }
    }

    private LocalTime cursorAt(List<ItineraryNode> schedule, int start) {
        if (start <= 0) {
            return resolveStart(schedule.get(0));
        }
        ItineraryNode node = schedule.get(start);
        LocalTime parsed = parseClock(node == null ? null : firstFilled(node.getArrivalTime(), node.getEstimatedArrival()));
        if (parsed != null) {
            return parsed;
        }
        LocalTime cursor = resolveStart(schedule.get(0));
        for (int i = 0; i < start; i++) {
            ItineraryNode previous = schedule.get(i);
            if (previous == null) {
                continue;
            }
            cursor = OpeningHoursParser.plusMinutesClamped(
                    cursor, resolveStayMinutes(previous) + resolveTransitMinutes(previous));
        }
        return cursor;
    }

    private static String firstFilled(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    static Collision evaluate(LocalTime arrival, DayHours hours) {
        if (arrival == null || hours == null || !hours.known()) {
            return new Collision(OpenStatus.UNKNOWN, null, null);
        }
        if (hours.closedAllDay()) {
            return new Collision(OpenStatus.CLOSED, null, null);
        }
        if (hours.open24h()) {
            return new Collision(OpenStatus.OPEN, "00:00", "24:00");
        }
        if (hours.windows() == null || hours.windows().isEmpty()) {
            return new Collision(OpenStatus.UNKNOWN, null, null);
        }
        Window matching = null;
        Window last = null;
        for (Window window : hours.windows()) {
            last = window;
            if (window.contains(arrival)) {
                matching = window;
                break;
            }
        }
        if (matching != null) {
            String open = OpeningHoursParser.formatClock(matching.open());
            String close = OpeningHoursParser.formatClock(matching.close());
            if (matching.minutesUntilClose(arrival) < CLOSING_SOON_MINUTES) {
                return new Collision(OpenStatus.CLOSING_SOON, open, close);
            }
            return new Collision(OpenStatus.OPEN, open, close);
        }
        Window first = hours.windows().isEmpty() ? null : hours.windows().get(0);
        Window relevant = arrival.isBefore(first == null ? arrival : first.open()) ? first : last;
        return new Collision(
                OpenStatus.CLOSED,
                relevant == null ? null : OpeningHoursParser.formatClock(relevant.open()),
                relevant == null ? null : OpeningHoursParser.formatClock(relevant.close()));
    }

    LocalTime resolveStart(ItineraryNode first) {
        LocalTime parsed = parseClock(first == null ? null : first.getArrivalTime());
        if (parsed != null) {
            return parsed;
        }
        LocalTime now = LocalTime.now(clock).withSecond(0).withNano(0);
        int roundedMinute = ((now.getMinute() + 4) / 5) * 5;
        if (roundedMinute >= 60) {
            now = now.plusHours(1).withMinute(0);
        } else {
            now = now.withMinute(roundedMinute);
        }
        return now.isBefore(DEFAULT_START) ? DEFAULT_START : now;
    }

    static int resolveStayMinutes(ItineraryNode node) {
        int parsed = parseStayMinutes(node == null ? null : node.getStayDuration());
        if (parsed > 0 && parsed <= 240) {
            return parsed;
        }
        return defaultStayMinutes(node);
    }

    static int defaultStayMinutes(ItineraryNode node) {
        String blob = ((node == null ? "" : nullToEmpty(node.getCategory())) + " "
                + (node == null ? "" : nullToEmpty(node.getCategoryEn())) + " "
                + (node == null ? "" : nullToEmpty(node.getCategoryZh())) + " "
                + (node == null ? "" : nullToEmpty(node.getPoiName())))
                .toLowerCase(Locale.ROOT);
        if (blob.contains("cafe") || blob.contains("coffee") || blob.contains("bakery")
                || blob.contains("brunch") || blob.contains("咖啡") || blob.contains("烘焙")) {
            return CAFE_STAY_MINUTES;
        }
        if (blob.contains("park") || blob.contains("market") || blob.contains("garden")
                || blob.contains("reserve") || blob.contains("公园") || blob.contains("集市")
                || blob.contains("市集")) {
            return PARK_STAY_MINUTES;
        }
        return DEFAULT_STAY_MINUTES;
    }

    static int resolveTransitMinutes(ItineraryNode node) {
        if (node == null) {
            return 0;
        }
        TransitOptions options = node.getTransitOptions();
        TransitModeOption option = pick(options, node.getSelectedMode());
        if (option != null && option.getDurationMinutes() != null) {
            return ItineraryTransitPlanner.clampHopMinutes(
                    option.getDurationMinutes(), option.getDistanceMeters());
        }
        return 0;
    }

    static TransitModeOption pick(TransitOptions options, String mode) {
        if (options == null) {
            return null;
        }
        if (ItineraryTransitPlanner.MODE_DRIVING.equals(mode)) {
            return options.getDriving();
        }
        if (ItineraryTransitPlanner.MODE_TRANSIT.equals(mode)) {
            return options.getTransit();
        }
        return options.getWalking();
    }

    static RealPlaceDto matchPlace(String poiName, List<RealPlaceDto> places) {
        if (poiName == null || poiName.isBlank() || places == null) {
            return null;
        }
        String key = poiName.trim().toLowerCase(Locale.ROOT);
        RealPlaceDto best = null;
        int bestScore = 0;
        for (RealPlaceDto place : places) {
            if (place == null || place.getTitle() == null || place.getTitle().isBlank()) {
                continue;
            }
            String title = place.getTitle().trim().toLowerCase(Locale.ROOT);
            int score = 0;
            if (title.equals(key)) {
                return place;
            }
            if (key.contains(title) || title.contains(key)) {
                score = 80 + Math.min(title.length(), key.length());
            } else {
                score = tokenOverlap(key, title) * 20;
            }
            if (score > bestScore) {
                bestScore = score;
                best = place;
            }
        }
        return bestScore >= 20 ? best : null;
    }

    static boolean isAlwaysOpenVenue(ItineraryNode node, RealPlaceDto place) {
        String blob = ((node == null ? "" : nullToEmpty(node.getPoiName())) + " "
                + (node == null ? "" : nullToEmpty(node.getCategory())) + " "
                + (node == null ? "" : nullToEmpty(node.getCategoryEn())) + " "
                + (node == null ? "" : nullToEmpty(node.getCategoryZh())) + " "
                + (place == null ? "" : nullToEmpty(place.getTitle())) + " "
                + (place == null ? "" : nullToEmpty(place.getCategory())))
                .toLowerCase(Locale.ROOT);
        return OUTDOOR.matcher(blob).find();
    }

    private static final Pattern OUTDOOR = Pattern.compile(
            "\\b(park|lookout|viewpoint|beach|garden|reserve|trail|headland|promenade|foreshore|harbour walk)\\b"
                    + "|公园|观景|海滩|步道|绿地|海滨",
            Pattern.CASE_INSENSITIVE);

    private static int tokenOverlap(String left, String right) {
        java.util.Set<String> a = tokens(left);
        java.util.Set<String> b = tokens(right);
        a.retainAll(b);
        return a.size();
    }

    private static java.util.Set<String> tokens(String raw) {
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
        if (raw == null) {
            return tokens;
        }
        for (String part : raw.toLowerCase(Locale.ROOT).split("[^a-z0-9\\u4e00-\\u9fff]+")) {
            if (part.length() < 3 || STOP_WORDS.contains(part)) {
                continue;
            }
            tokens.add(part);
        }
        return tokens;
    }

    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "the", "and", "for", "sydney", "nsw", "australia", "street", "road");

    static LocalTime parseClock(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = CLOCK.matcher(raw.trim());
        if (!matcher.matches()) {
            return OpeningHoursParser.parseClock(raw);
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    static int parseStayMinutes(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        Matcher hours = STAY_HOURS.matcher(raw);
        Matcher minutes = STAY_MINUTES.matcher(raw);
        int total = 0;
        if (hours.find()) {
            total += Math.round(Float.parseFloat(hours.group(1)) * 60f);
        }
        if (minutes.find()) {
            total += Integer.parseInt(minutes.group(1));
        }
        return total;
    }

    private static String defaultStayText(ItineraryNode node, LanguageEnum language) {
        int minutes = defaultStayMinutes(node);
        return language.isZh() ? "停留" + minutes + "分钟" : minutes + " min stay";
    }

    private static String warning(Collision collision, String arrival, String hoursText, LanguageEnum language) {
        return switch (collision.status()) {
            case CLOSED -> language.text(
                    "预计 " + arrival + " 抵达，该店 "
                            + (collision.closesAt() == null ? "当天" : collision.closesAt())
                            + " 打烊（闭店风险）",
                    "Arrive " + arrival + ", but the venue closes"
                            + (collision.closesAt() == null ? "" : " at " + collision.closesAt())
                            + " (closed-door risk)");
            case CLOSING_SOON -> language.text(
                    "预计 " + arrival + " 抵达，该店 " + collision.closesAt() + " 打烊（不足30分钟）",
                    "Arrive " + arrival + "; closes at " + collision.closesAt() + " (under 30 min left)");
            case OPEN -> hoursText != null && !hoursText.isBlank()
                    ? hoursText
                    : language.text(
                    collision.closesAt() == null || "24:00".equals(collision.closesAt())
                            ? "营业中 · 全天开放"
                            : "营业中 · 通常 " + collision.closesAt() + " 打烊",
                    collision.closesAt() == null || "24:00".equals(collision.closesAt())
                            ? "Open · 24 hours"
                            : "Open · usually closes at " + collision.closesAt());
            case UNKNOWN -> language.text("营业时间未知", "Opening hours unknown");
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record Collision(OpenStatus status, String opensAt, String closesAt) {
    }
}
