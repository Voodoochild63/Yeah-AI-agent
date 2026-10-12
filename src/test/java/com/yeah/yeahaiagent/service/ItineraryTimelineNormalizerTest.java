package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import com.yeah.yeahaiagent.model.dto.TransitModeOption;
import com.yeah.yeahaiagent.model.dto.TransitOptions;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("时间线顺推与日落锚点")
class ItineraryTimelineNormalizerTest {

    private final ItineraryTimelineNormalizer normalizer = new ItineraryTimelineNormalizer();

    @Test
    @DisplayName("21:24 之后的下午日落会被提前，晚间活动排在日落离开之后")
    void sortsInvertedSunsetBeforeEveningAndCascades() {
        ItineraryNode dinner = node("Cho Cho San", "Fine Dining", "21:24", "停留75分钟");
        dinner.setSpotCategory("DINING");
        dinner.setStayDuration("停留75分钟");
        ItineraryNode sunset = node("Mrs Macquaries Chair", "Sunset lookout", "16:33", "停留45分钟");
        sunset.setSpotCategory("SUNSET");
        sunset.setStayDuration("停留57分钟");
        sunset.setTransitOptions(overflowTransit());
        ItineraryNode bar = node("Maybe Sammy", "Cocktail bar", "22:10", "停留60分钟");
        bar.setSpotCategory("BAR");
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSunsetEvaluation(new SunsetEvaluation(
                true, "17:11", "16:56 - 17:26", 40, 10, "ok", "Mrs Macquaries Chair", "tip"));
        plan.setSchedule(new ArrayList<>(List.of(dinner, sunset, bar)));

        normalizer.apply(plan, LanguageEnum.ZH);

        List<ItineraryNode> schedule = plan.getSchedule();
        assertThat(schedule.get(0).getPoiName()).isEqualTo("Mrs Macquaries Chair");
        assertThat(schedule.get(1).getPoiName()).isEqualTo("Cho Cho San");
        assertThat(schedule.get(2).getPoiName()).isEqualTo("Maybe Sammy");
        LocalTime sunsetLeave = LocalTime.parse(schedule.get(0).getArrivalTime())
                .plusMinutes(parseStay(schedule.get(0).getStayDuration()));
        LocalTime dinnerArrival = LocalTime.parse(schedule.get(1).getArrivalTime());
        LocalTime barArrival = LocalTime.parse(schedule.get(2).getArrivalTime());
        assertThat(dinnerArrival).isAfterOrEqualTo(sunsetLeave);
        assertThat(barArrival).isAfterOrEqualTo(dinnerArrival);
        assertThat(schedule.get(0).getTransitOptions().getTransit().getDurationMinutes()).isLessThanOrEqualTo(120);
        assertThat(schedule.get(0).getTransitOptions().getTransit().getDistanceText()).doesNotContain("24535");
    }

    @Test
    @DisplayName("相邻节点 start = 上一站离开 + 通勤，时间单调递增")
    void nextStartEqualsPreviousDeparturePlusHop() {
        ItineraryNode first = node("White Rabbit Gallery", "Gallery", "14:00", "停留45分钟");
        first.setSpotCategory("ATTRACTION");
        first.setStayDuration("停留45分钟");
        TransitModeOption hop = new TransitModeOption("12分钟", "900m", 12, 900);
        first.setTransitOptions(new TransitOptions(hop, hop, hop));
        first.setSelectedMode(ItineraryTransitPlanner.MODE_WALKING);
        ItineraryNode second = node("Observatory Hill Park", "Park stroll", "16:00", "停留40分钟");
        second.setSpotCategory("PARK");
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(first, second)));

        normalizer.apply(plan, LanguageEnum.ZH);

        assertThat(plan.getSchedule().get(1).getArrivalTime()).isEqualTo("14:57");
    }

    @Test
    @DisplayName("插入到散步前不打乱顺序，时间从头顺推且不倒流")
    void insertBeforeWalkKeepsOrderAndMonotonicTimes() {
        ItineraryNode brunch = node("Grounds", "Brunch", "13:30", "停留45分钟");
        brunch.setSpotCategory("CAFE");
        TransitModeOption hop = new TransitModeOption("10分钟", "800m", 10, 800);
        brunch.setTransitOptions(new TransitOptions(hop, hop, hop));
        ItineraryNode korean = node("Moon Park", "Korean restaurant", null, "停留75分钟");
        korean.setSpotCategory("KOREAN_RESTAURANT");
        korean.setTransitOptions(new TransitOptions(hop, hop, hop));
        ItineraryNode walk = node("Bondi to Bronte Coastal Walk", "Walk", "15:30", "停留40分钟");
        walk.setSpotCategory("WALK");
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(brunch, korean, walk)));

        normalizer.rechainFromStart(plan, LanguageEnum.ZH);

        List<ItineraryNode> schedule = plan.getSchedule();
        assertThat(schedule.get(0).getPoiName()).isEqualTo("Grounds");
        assertThat(schedule.get(1).getPoiName()).isEqualTo("Moon Park");
        assertThat(schedule.get(2).getPoiName()).isEqualTo("Bondi to Bronte Coastal Walk");
        assertThat(schedule.get(0).getArrivalTime()).isEqualTo("13:30");
        LocalTime first = LocalTime.parse(schedule.get(0).getArrivalTime());
        LocalTime second = LocalTime.parse(schedule.get(1).getArrivalTime());
        LocalTime third = LocalTime.parse(schedule.get(2).getArrivalTime());
        assertThat(second).isAfterOrEqualTo(first);
        assertThat(third).isAfterOrEqualTo(second);
        assertThat(schedule.get(1).getStayDuration()).contains("75");
    }

    private static int parseStay(String stay) {
        return ItineraryOpeningHoursChecker.resolveStayMinutes(node("x", "y", "10:00", stay));
    }

    private static TransitOptions overflowTransit() {
        TransitModeOption dirty = new TransitModeOption("58888分钟", "公交24535站", 58888, 19_600_000);
        return new TransitOptions(dirty, dirty, dirty);
    }

    private static ItineraryNode node(String name, String category, String arrival, String stay) {
        ItineraryNode node = new ItineraryNode();
        node.setPoiName(name);
        node.setCategory(category);
        node.setArrivalTime(arrival);
        node.setEstimatedArrival(arrival);
        node.setStayDuration(stay);
        return node;
    }
}
