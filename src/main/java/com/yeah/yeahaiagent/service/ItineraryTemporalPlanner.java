package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.model.enums.SpotCategory;
import com.yeah.yeahaiagent.util.OpeningHoursParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 落日黄金窗口是带时域锚点的 Stop：窗口内禁止正餐，晚饭必须排在窗口结束之后。
 */
@Component
@Slf4j
public class ItineraryTemporalPlanner {

    static final LocalTime DEFAULT_LOCK_START = LocalTime.of(17, 15);
    static final LocalTime DEFAULT_LOCK_END = LocalTime.of(18, 15);
    private static final int LOCK_START_PAD_MINUTES = 11;
    private static final int LOCK_END_PAD_MINUTES = 4;
    private static final Pattern WINDOW = Pattern.compile(
            "(\\d{1,2}):(\\d{2})\\s*[-–—]\\s*(\\d{1,2}):(\\d{2})");

    public record SunsetWindow(LocalTime lockStart, LocalTime lockEnd, LocalTime sunsetTime) {
        public boolean contains(LocalTime clock) {
            return clock != null && !clock.isBefore(lockStart) && clock.isBefore(lockEnd);
        }
    }

    /**
     * 把落日节点对齐到黄金窗口，并把窗口内的正餐顺延到 lockEnd + 通勤。
     *
     * @return 第一个被改动的下标；无需改动时为 -1
     */
    public int apply(ItineraryPlan plan, LanguageEnum language) {
        if (plan == null || plan.getSchedule() == null || plan.getSchedule().isEmpty()) {
            return -1;
        }
        SunsetWindow window = resolveWindow(plan.getSunsetEvaluation());
        if (window == null) {
            return -1;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        List<ItineraryNode> schedule = plan.getSchedule();
        int sunsetIndex = indexOfSunset(schedule);
        if (sunsetIndex < 0) {
            return shiftDiningOutOfWindow(schedule, -1, window, lang);
        }
        alignSunsetStay(schedule.get(sunsetIndex), window, lang);
        int firstChanged = sunsetIndex;
        int diningChanged = shiftDiningOutOfWindow(schedule, sunsetIndex, window, lang);
        if (diningChanged >= 0) {
            firstChanged = Math.min(firstChanged, diningChanged);
        }
        cascadeAfter(schedule, sunsetIndex);
        log.info("sunset window locked. start={} end={} sunsetIndex={}",
                window.lockStart(), window.lockEnd(), sunsetIndex);
        return firstChanged;
    }

    public static SunsetWindow resolveWindow(SunsetEvaluation evaluation) {
        if (evaluation == null || !evaluation.recommended()) {
            return null;
        }
        LocalTime sunsetTime = ItineraryOpeningHoursChecker.parseClock(evaluation.sunsetTime());
        Matcher matcher = evaluation.goldenHourWindow() == null
                ? null
                : WINDOW.matcher(evaluation.goldenHourWindow().trim());
        LocalTime goldenStart = null;
        LocalTime goldenEnd = null;
        if (matcher != null && matcher.find()) {
            goldenStart = clock(matcher.group(1), matcher.group(2));
            goldenEnd = clock(matcher.group(3), matcher.group(4));
        }
        LocalTime lockStart = goldenStart != null
                ? goldenStart.minusMinutes(LOCK_START_PAD_MINUTES)
                : DEFAULT_LOCK_START;
        LocalTime lockEnd = goldenEnd != null
                ? goldenEnd.plusMinutes(LOCK_END_PAD_MINUTES)
                : DEFAULT_LOCK_END;
        if (lockEnd.isBefore(lockStart) || lockEnd.equals(lockStart)) {
            lockEnd = lockStart.plusMinutes(45);
        }
        return new SunsetWindow(lockStart, lockEnd, sunsetTime);
    }

    public static LocalTime earliestDiningArrival(SunsetEvaluation evaluation, int hopMinutes) {
        SunsetWindow window = resolveWindow(evaluation);
        if (window == null) {
            return null;
        }
        return window.lockEnd().plusMinutes(Math.max(hopMinutes, 1));
    }

    public static int indexOfSunset(List<ItineraryNode> schedule) {
        if (schedule == null) {
            return -1;
        }
        for (int i = 0; i < schedule.size(); i++) {
            if (isSunsetNode(schedule.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isSunsetNode(ItineraryNode node) {
        if (node == null) {
            return false;
        }
        if (SpotCategory.SUNSET == SpotCategory.from(node.getSpotCategory())) {
            return true;
        }
        String blob = blob(node);
        return blob.contains("sunset")
                || blob.contains("落日")
                || blob.contains("日落")
                || blob.contains("晚霞")
                || blob.contains("golden hour");
    }

    public static boolean isLongDining(ItineraryNode node) {
        if (node == null) {
            return false;
        }
        SpotCategory category = SpotCategory.from(firstFilled(node.getSpotCategory(), blob(node)));
        if (category.longDining()) {
            return true;
        }
        int stay = ItineraryOpeningHoursChecker.resolveStayMinutes(node);
        return stay >= 70 && SpotCategory.looksLikeDining(blob(node));
    }

    public static int findDiningOverlappingLock(List<ItineraryNode> schedule, SunsetEvaluation evaluation) {
        SunsetWindow window = resolveWindow(evaluation);
        if (window == null || schedule == null) {
            return -1;
        }
        for (int i = 0; i < schedule.size(); i++) {
            ItineraryNode node = schedule.get(i);
            if (!isLongDining(node) || isSunsetNode(node)) {
                continue;
            }
            LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                    firstFilled(node.getArrivalTime(), node.getEstimatedArrival()));
            if (arrival == null) {
                continue;
            }
            if (window.contains(arrival) || arrival.equals(window.lockStart())
                    || (arrival.isBefore(window.lockEnd()) && !arrival.isBefore(window.lockStart().minusMinutes(20)))) {
                return i;
            }
        }
        return -1;
    }

    static LocalTime leaveTime(ItineraryNode node, SunsetEvaluation evaluation) {
        LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                firstFilled(node == null ? null : node.getArrivalTime(),
                        node == null ? null : node.getEstimatedArrival()));
        if (arrival == null) {
            return null;
        }
        LocalTime natural = arrival.plusMinutes(ItineraryOpeningHoursChecker.resolveStayMinutes(node));
        if (!isSunsetNode(node)) {
            return natural;
        }
        SunsetWindow window = resolveWindow(evaluation);
        if (window == null) {
            return natural;
        }
        return natural.isBefore(window.lockEnd()) ? window.lockEnd() : natural;
    }

    private static void alignSunsetStay(ItineraryNode sunset, SunsetWindow window, LanguageEnum language) {
        LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                firstFilled(sunset.getArrivalTime(), sunset.getEstimatedArrival()));
        if (arrival == null) {
            String clock = OpeningHoursParser.formatClock(window.lockStart());
            sunset.setArrivalTime(clock);
            sunset.setEstimatedArrival(clock);
            arrival = window.lockStart();
        }
        LocalTime leave = arrival.isBefore(window.lockEnd()) ? window.lockEnd() : arrival.plusMinutes(45);
        int stay = Math.max(30, minutesBetween(arrival, leave));
        sunset.setStayDuration(language.isZh() ? "停留" + stay + "分钟" : stay + " min stay");
        sunset.setSpotCategory(SpotCategory.SUNSET.name());
    }

    private int shiftDiningOutOfWindow(List<ItineraryNode> schedule, int sunsetIndex,
                                       SunsetWindow window, LanguageEnum language) {
        int firstChanged = -1;
        for (int i = 0; i < schedule.size(); i++) {
            if (i == sunsetIndex) {
                continue;
            }
            ItineraryNode node = schedule.get(i);
            if (!isLongDining(node)) {
                continue;
            }
            LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                    firstFilled(node.getArrivalTime(), node.getEstimatedArrival()));
            if (arrival == null || !arrival.isBefore(window.lockEnd())) {
                continue;
            }
            int hop = 1;
            if (sunsetIndex >= 0 && i == sunsetIndex + 1) {
                hop = Math.max(1, ItineraryOpeningHoursChecker.resolveTransitMinutes(schedule.get(sunsetIndex)));
            } else if (i > 0) {
                hop = Math.max(1, ItineraryOpeningHoursChecker.resolveTransitMinutes(schedule.get(i - 1)));
            }
            LocalTime shifted = window.lockEnd().plusMinutes(hop);
            String clock = OpeningHoursParser.formatClock(shifted);
            node.setArrivalTime(clock);
            node.setEstimatedArrival(clock);
            if (firstChanged < 0) {
                firstChanged = i;
            }
            log.info("shifted dining past sunset lock. poi={} from={} to={} lockEnd={}",
                    node.getPoiName(), arrival, clock, window.lockEnd());
        }
        return firstChanged;
    }

    private static void cascadeAfter(List<ItineraryNode> schedule, int fromIndex) {
        if (fromIndex < 0 || fromIndex >= schedule.size()) {
            return;
        }
        for (int i = fromIndex; i < schedule.size() - 1; i++) {
            ItineraryNode current = schedule.get(i);
            ItineraryNode next = schedule.get(i + 1);
            if (current == null || next == null) {
                continue;
            }
            LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                    firstFilled(current.getArrivalTime(), current.getEstimatedArrival()));
            if (arrival == null) {
                continue;
            }
            LocalTime nextArrival = OpeningHoursParser.plusMinutesClamped(
                    arrival,
                    ItineraryOpeningHoursChecker.resolveStayMinutes(current)
                            + Math.max(1, ItineraryOpeningHoursChecker.resolveTransitMinutes(current)));
            String clock = OpeningHoursParser.formatClock(nextArrival);
            next.setArrivalTime(clock);
            next.setEstimatedArrival(clock);
        }
    }

    private static int minutesBetween(LocalTime start, LocalTime end) {
        return Math.max(0, end.toSecondOfDay() / 60 - start.toSecondOfDay() / 60);
    }

    private static LocalTime clock(String hour, String minute) {
        int h = Integer.parseInt(hour);
        int m = Integer.parseInt(minute);
        if (h > 23 || m > 59) {
            return null;
        }
        return LocalTime.of(h, m);
    }

    private static String blob(ItineraryNode node) {
        return String.join(" ",
                nullToEmpty(node.getPoiName()),
                nullToEmpty(node.getCategory()),
                nullToEmpty(node.getCategoryZh()),
                nullToEmpty(node.getCategoryEn()),
                nullToEmpty(node.getSpotCategory())).toLowerCase(Locale.ROOT);
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
