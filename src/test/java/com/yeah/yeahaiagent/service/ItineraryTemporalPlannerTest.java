package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("落日黄金窗口时域锚点")
class ItineraryTemporalPlannerTest {

    private final ItineraryTemporalPlanner planner = new ItineraryTemporalPlanner();

    @Test
    @DisplayName("17:26-18:11 黄金窗锁定为 17:15-18:15")
    void padsGoldenHourToLockedWindow() {
        SunsetEvaluation evaluation = new SunsetEvaluation(
                true, "17:41", "17:26 - 18:11", 40, 10, "ok", "spot", "tip");
        ItineraryTemporalPlanner.SunsetWindow window = ItineraryTemporalPlanner.resolveWindow(evaluation);
        assertThat(window.lockStart()).hasToString("17:15");
        assertThat(window.lockEnd()).hasToString("18:15");
    }

    @Test
    @DisplayName("落日停留覆盖到窗口结束，晚饭不得早于 18:15")
    void dinnerCannotStartInsideSunsetWindow() {
        ItineraryNode sunset = node("Mrs Macquaries Chair", "Sunset lookout", "16:52", "45分钟");
        sunset.setSpotCategory("SUNSET");
        ItineraryNode dinner = node("Cho Cho San", "Fine Dining", "17:31", "停留75分钟");
        dinner.setSpotCategory("DINING");
        dinner.setStayDuration("停留75分钟");
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSunsetEvaluation(new SunsetEvaluation(
                true, "17:41", "17:26 - 18:11", 40, 10, "ok", "Mrs Macquaries Chair", "tip"));
        plan.setSchedule(new ArrayList<>(List.of(sunset, dinner)));

        planner.apply(plan, LanguageEnum.ZH);

        assertThat(plan.getSchedule().get(0).getStayDuration()).contains("83");
        assertThat(plan.getSchedule().get(1).getArrivalTime()).isGreaterThanOrEqualTo("18:15");
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
