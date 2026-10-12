package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import com.yeah.yeahaiagent.model.dto.TransitOptions;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.model.enums.SpotCategory;
import com.yeah.yeahaiagent.util.OpeningHoursParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 返回前端前：夹紧脏通勤、日落排在下午、晚间活动在日落离开之后、时间线严格顺推。
 */
@Component
@Slf4j
public class ItineraryTimelineNormalizer {

    public void apply(ItineraryPlan plan, LanguageEnum language) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        clampTransits(schedule, lang);
        placeSunsetThenEvening(schedule, plan.getSunsetEvaluation());
        lockSunsetAfternoon(schedule, plan.getSunsetEvaluation(), lang);
        rechainFromStart(plan, lang);
        log.info("timeline normalized. size={} sunsetIndex={}",
                schedule.size(), ItineraryTemporalPlanner.indexOfSunset(schedule));
    }

    /**
     * 保持当前 List 顺序，从第一站开始线性顺推。REPLACE / APPEND / DELETE 之后只走这条路径。
     */
    public void rechainFromStart(ItineraryPlan plan, LanguageEnum language) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        clampTransits(schedule, lang);
        applyStayByType(schedule, lang);
        seedFirstArrival(schedule);
        int sunsetIndex = ItineraryTemporalPlanner.indexOfSunset(schedule);
        if (sunsetIndex < 0) {
            cascadeStrict(schedule, 0, schedule.size());
        } else {
            cascadeStrict(schedule, 0, sunsetIndex);
            lockSunsetAfternoon(schedule, plan.getSunsetEvaluation(), lang);
            LocalTime computed = previousLeavePlusHop(schedule, sunsetIndex);
            LocalTime locked = ItineraryOpeningHoursChecker.parseClock(
                    firstFilled(schedule.get(sunsetIndex).getArrivalTime(),
                            schedule.get(sunsetIndex).getEstimatedArrival()));
            if (computed != null && locked != null && computed.isAfter(locked)) {
                String clock = OpeningHoursParser.formatClock(computed);
                schedule.get(sunsetIndex).setArrivalTime(clock);
                schedule.get(sunsetIndex).setEstimatedArrival(clock);
            }
            cascadeStrict(schedule, sunsetIndex, schedule.size());
        }
        assertEveningAfterSunset(schedule, plan.getSunsetEvaluation());
        assertMonotonic(schedule);
        log.info("timeline rechained from start. size={}", schedule.size());
    }

    static void clampTransits(List<ItineraryNode> schedule, LanguageEnum language) {
        if (schedule == null) {
            return;
        }
        for (ItineraryNode node : schedule) {
            if (node == null) {
                continue;
            }
            TransitOptions options = node.getTransitOptions();
            if (options == null) {
                continue;
            }
            ItineraryTransitPlanner.sanitize(options, language);
            if (node.getSelectedMode() != null) {
                node.setTransitToNext(ItineraryTransitPlanner.formatSelected(
                        options, node.getSelectedMode(), language));
            }
        }
    }

    static void placeSunsetThenEvening(List<ItineraryNode> schedule, SunsetEvaluation evaluation) {
        int sunsetIndex = ItineraryTemporalPlanner.indexOfSunset(schedule);
        if (sunsetIndex < 0) {
            return;
        }
        ItineraryNode sunset = schedule.get(sunsetIndex);
        LocalTime leave = sunsetLeave(sunset, evaluation);
        List<ItineraryNode> before = new ArrayList<>();
        List<ItineraryNode> evening = new ArrayList<>();
        for (int i = 0; i < schedule.size(); i++) {
            if (i == sunsetIndex) {
                continue;
            }
            ItineraryNode node = schedule.get(i);
            if (isEveningActivity(node, leave)) {
                evening.add(node);
            } else {
                before.add(node);
            }
        }
        sortByArrival(before);
        sortByArrival(evening);
        schedule.clear();
        schedule.addAll(before);
        schedule.add(sunset);
        schedule.addAll(evening);
    }

    static void applyStayByType(List<ItineraryNode> schedule, LanguageEnum language) {
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        if (schedule == null) {
            return;
        }
        for (ItineraryNode node : schedule) {
            if (node == null || ItineraryTemporalPlanner.isSunsetNode(node)) {
                continue;
            }
            SpotCategory category = SpotCategory.from(firstFilled(
                    firstFilled(node.getSpotCategory(), node.getCategory()), node.getCategoryEn()));
            int stay = category == SpotCategory.OTHER
                    ? ItineraryOpeningHoursChecker.resolveStayMinutes(node)
                    : category.stayMinutes();
            if (category == SpotCategory.DESSERT) {
                stay = 25;
            } else if (stay < 20 || stay > 180) {
                stay = category.scenicWalk() ? 40 : (category.longDining() ? 75 : 45);
            }
            node.setStayDuration(lang.isZh() ? "停留" + stay + "分钟" : stay + " min stay");
            if (node.getSpotCategory() == null || node.getSpotCategory().isBlank()) {
                node.setSpotCategory(category.name());
            }
        }
    }

    static LocalTime previousLeavePlusHop(List<ItineraryNode> schedule, int index) {
        if (schedule == null || index <= 0 || index >= schedule.size()) {
            return null;
        }
        ItineraryNode previous = schedule.get(index - 1);
        LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                firstFilled(previous.getArrivalTime(), previous.getEstimatedArrival()));
        if (arrival == null) {
            return null;
        }
        return OpeningHoursParser.plusMinutesClamped(arrival,
                ItineraryOpeningHoursChecker.resolveStayMinutes(previous)
                        + Math.max(1, ItineraryOpeningHoursChecker.resolveTransitMinutes(previous)));
    }

    static void seedFirstArrival(List<ItineraryNode> schedule) {
        if (schedule == null || schedule.isEmpty() || schedule.get(0) == null) {
            return;
        }
        LocalTime start = ItineraryOpeningHoursChecker.parseClock(
                firstFilled(schedule.get(0).getArrivalTime(), schedule.get(0).getEstimatedArrival()));
        if (start == null) {
            for (ItineraryNode node : schedule) {
                start = ItineraryOpeningHoursChecker.parseClock(
                        firstFilled(node == null ? null : node.getArrivalTime(),
                                node == null ? null : node.getEstimatedArrival()));
                if (start != null) {
                    break;
                }
            }
        }
        if (start == null) {
            start = LocalTime.of(13, 30);
        }
        String clock = OpeningHoursParser.formatClock(start);
        schedule.get(0).setArrivalTime(clock);
        schedule.get(0).setEstimatedArrival(clock);
    }

    static void assertMonotonic(List<ItineraryNode> schedule) {
        LocalTime previous = null;
        for (ItineraryNode node : schedule) {
            LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                    firstFilled(node == null ? null : node.getArrivalTime(),
                            node == null ? null : node.getEstimatedArrival()));
            if (arrival == null) {
                continue;
            }
            if (previous != null && arrival.isBefore(previous)) {
                String clock = OpeningHoursParser.formatClock(previous);
                node.setArrivalTime(clock);
                node.setEstimatedArrival(clock);
                arrival = previous;
            }
            previous = arrival;
        }
    }

    static void sortByArrival(List<ItineraryNode> nodes) {
        if (nodes == null || nodes.size() < 2) {
            return;
        }
        nodes.sort(Comparator.comparing(ItineraryTimelineNormalizer::arrivalOrMax));
    }

    static void cascadeStrict(List<ItineraryNode> schedule, int fromIndex, int toExclusive) {
        if (schedule == null || fromIndex < 0) {
            return;
        }
        int end = Math.min(toExclusive, schedule.size()) - 1;
        for (int i = fromIndex; i < end; i++) {
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
            int stay = ItineraryOpeningHoursChecker.resolveStayMinutes(current);
            int hop = Math.max(1, ItineraryOpeningHoursChecker.resolveTransitMinutes(current));
            LocalTime nextArrival = OpeningHoursParser.plusMinutesClamped(arrival, stay + hop);
            String clock = OpeningHoursParser.formatClock(nextArrival);
            next.setArrivalTime(clock);
            next.setEstimatedArrival(clock);
        }
    }

    static void lockSunsetAfternoon(List<ItineraryNode> schedule, SunsetEvaluation evaluation, LanguageEnum language) {
        int sunsetIndex = ItineraryTemporalPlanner.indexOfSunset(schedule);
        if (sunsetIndex < 0) {
            return;
        }
        ItineraryNode sunset = schedule.get(sunsetIndex);
        ItineraryTemporalPlanner.SunsetWindow window = ItineraryTemporalPlanner.resolveWindow(evaluation);
        LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                firstFilled(sunset.getArrivalTime(), sunset.getEstimatedArrival()));
        LocalTime afternoonStart = window != null ? window.lockStart() : LocalTime.of(16, 30);
        LocalTime afternoonEnd = window != null ? window.lockEnd() : LocalTime.of(17, 30);
        if (arrival == null || arrival.isBefore(LocalTime.of(15, 0)) || arrival.isAfter(LocalTime.of(19, 0))) {
            arrival = afternoonStart;
        }
        String clock = OpeningHoursParser.formatClock(arrival);
        sunset.setArrivalTime(clock);
        sunset.setEstimatedArrival(clock);
        LocalTime leave = arrival.isBefore(afternoonEnd) ? afternoonEnd : arrival.plusMinutes(45);
        int stay = Math.max(30, minutesBetween(arrival, leave));
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        sunset.setStayDuration(lang.isZh() ? "停留" + stay + "分钟" : stay + " min stay");
        sunset.setSpotCategory(SpotCategory.SUNSET.name());
    }

    static void assertEveningAfterSunset(List<ItineraryNode> schedule, SunsetEvaluation evaluation) {
        int sunsetIndex = ItineraryTemporalPlanner.indexOfSunset(schedule);
        if (sunsetIndex < 0) {
            cascadeStrict(schedule, 0, schedule.size());
            return;
        }
        LocalTime leave = sunsetLeave(schedule.get(sunsetIndex), evaluation);
        if (leave == null) {
            return;
        }
        for (int i = sunsetIndex + 1; i < schedule.size(); i++) {
            ItineraryNode node = schedule.get(i);
            LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                    firstFilled(node.getArrivalTime(), node.getEstimatedArrival()));
            if (arrival == null || arrival.isBefore(leave)) {
                ItineraryNode previous = schedule.get(i - 1);
                int hop = Math.max(1, ItineraryOpeningHoursChecker.resolveTransitMinutes(previous));
                LocalTime forced = OpeningHoursParser.plusMinutesClamped(leave, hop);
                String clock = OpeningHoursParser.formatClock(forced);
                node.setArrivalTime(clock);
                node.setEstimatedArrival(clock);
            }
        }
        cascadeStrict(schedule, sunsetIndex, schedule.size());
    }

    static boolean isEveningActivity(ItineraryNode node, LocalTime sunsetLeave) {
        if (node == null || ItineraryTemporalPlanner.isSunsetNode(node)) {
            return false;
        }
        if (ItinerarySafetyGuardrail.isAlcoholVenue(node)) {
            return true;
        }
        LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                firstFilled(node.getArrivalTime(), node.getEstimatedArrival()));
        if (ItineraryTemporalPlanner.isLongDining(node)
                && (arrival == null || !arrival.isBefore(LocalTime.of(16, 0)))) {
            return true;
        }
        String blob = blob(node);
        boolean nightWalk = SpotCategory.looksLikeWalk(blob)
                && (blob.contains("夜") || blob.contains("night") || blob.contains("晚间"));
        if (nightWalk) {
            return true;
        }
        return sunsetLeave != null && arrival != null && !arrival.isBefore(sunsetLeave);
    }

    private static LocalTime sunsetLeave(ItineraryNode sunset, SunsetEvaluation evaluation) {
        LocalTime leave = ItineraryTemporalPlanner.leaveTime(sunset, evaluation);
        if (leave != null) {
            return leave;
        }
        LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                firstFilled(sunset.getArrivalTime(), sunset.getEstimatedArrival()));
        if (arrival == null) {
            return LocalTime.of(17, 30);
        }
        return OpeningHoursParser.plusMinutesClamped(
                arrival, ItineraryOpeningHoursChecker.resolveStayMinutes(sunset));
    }

    private static LocalTime arrivalOrMax(ItineraryNode node) {
        LocalTime arrival = ItineraryOpeningHoursChecker.parseClock(
                firstFilled(node == null ? null : node.getArrivalTime(),
                        node == null ? null : node.getEstimatedArrival()));
        return arrival == null ? LocalTime.MAX : arrival;
    }

    private static int minutesBetween(LocalTime start, LocalTime end) {
        return Math.max(0, end.toSecondOfDay() / 60 - start.toSecondOfDay() / 60);
    }

    private static String blob(ItineraryNode node) {
        return String.join(" ",
                nullToEmpty(node.getPoiName()),
                nullToEmpty(node.getCategory()),
                nullToEmpty(node.getCategoryZh()),
                nullToEmpty(node.getCategoryEn()),
                nullToEmpty(node.getSpotCategory())).toLowerCase();
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
