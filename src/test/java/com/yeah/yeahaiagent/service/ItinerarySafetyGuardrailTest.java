package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.TransitModeOption;
import com.yeah.yeahaiagent.model.dto.TransitOptions;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.model.enums.SpotCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("饮酒场景安全出行守则")
class ItinerarySafetyGuardrailTest {

    private final ItinerarySafetyGuardrail guardrail = new ItinerarySafetyGuardrail();

    @Test
    @DisplayName("酒吧节点切断自驾、隐藏停车并提示勿酒驾")
    void barForbidsDrivingAndHidesParking() {
        ItineraryNode sunset = node("Mrs Macquaries Chair", "Sunset lookout");
        sunset.setSelectedMode(ItineraryTransitPlanner.MODE_DRIVING);
        sunset.setTransitOptions(options());
        sunset.setTransitToNext("驾车 8分钟");
        ItineraryNode bar = node("Maybe Sammy", "Cocktail bar");
        bar.setSpotCategory("BAR");
        bar.setParkingSearchKeyword("parking near Maybe Sammy");
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(sunset, bar)));

        guardrail.apply(plan, LanguageEnum.ZH);

        assertThat(sunset.getDrivingForbidden()).isTrue();
        assertThat(sunset.getAlcoholInvolved()).isNotEqualTo(Boolean.TRUE);
        assertThat(sunset.getSafetyWarning()).isNull();
        assertThat(sunset.getSelectedMode()).isNotEqualTo(ItineraryTransitPlanner.MODE_DRIVING);
        assertThat(sunset.getTransitOptions().drivingEnabled()).isFalse();
        assertThat(sunset.getTransitToNext()).contains("Uber");
        assertThat(bar.getAlcoholInvolved()).isTrue();
        assertThat(bar.getSafetyWarning()).contains("酒驾");
        assertThat(bar.getParkingInfo().hasParkingRecommendation()).isFalse();
    }

    @Test
    @DisplayName("饮酒警示只打在酒吧节点，不污染午餐/下午茶/观景")
    void alcoholWarningDoesNotSpreadToInnocentStops() {
        ItineraryNode lunch = node("Cho Cho San", "Fine Dining");
        lunch.setSpotCategory("DINING");
        lunch.setAlcoholInvolved(true);
        lunch.setSafetyWarning("🍷 涉及饮酒，请使用公共交通或网约车出行，切勿酒驾");
        ItineraryNode tea = node("The Grounds of Alexandria", "Afternoon tea");
        tea.setSpotCategory("TEA");
        tea.setAlcoholInvolved(true);
        tea.setSafetyWarning("🍷 涉及饮酒");
        ItineraryNode scenic = node("Mrs Macquaries Chair", "Scenic spot");
        scenic.setSpotCategory("SCENIC");
        scenic.setAlcoholInvolved(true);
        scenic.setSafetyWarning("🍷 涉及饮酒");
        ItineraryNode sunset = node("Mrs Macquaries Chair Sunset", "Sunset lookout");
        sunset.setSpotCategory("SUNSET");
        sunset.setSelectedMode(ItineraryTransitPlanner.MODE_DRIVING);
        sunset.setTransitOptions(options());
        ItineraryNode bar = node("Maybe Sammy", "Cocktail bar");
        bar.setSpotCategory("BAR");
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(lunch, tea, scenic, sunset, bar)));

        guardrail.apply(plan, LanguageEnum.ZH);

        assertThat(lunch.getAlcoholInvolved()).isFalse();
        assertThat(lunch.getSafetyWarning()).isNull();
        assertThat(tea.getAlcoholInvolved()).isFalse();
        assertThat(tea.getSafetyWarning()).isNull();
        assertThat(scenic.getAlcoholInvolved()).isFalse();
        assertThat(scenic.getSafetyWarning()).isNull();
        assertThat(sunset.getAlcoholInvolved()).isNotEqualTo(Boolean.TRUE);
        assertThat(sunset.getSafetyWarning()).isNull();
        assertThat(bar.getAlcoholInvolved()).isTrue();
        assertThat(bar.getSafetyWarning()).contains("酒驾");
        assertThat(ItinerarySafetyGuardrail.isAlcoholVenue(lunch)).isFalse();
        assertThat(ItinerarySafetyGuardrail.isAlcoholVenue(tea)).isFalse();
        assertThat(ItinerarySafetyGuardrail.isAlcoholVenue(scenic)).isFalse();
        assertThat(ItinerarySafetyGuardrail.isAlcoholVenue(sunset)).isFalse();
        assertThat(SpotCategory.looksLikeBar("scenic spot lunch afternoon tea 观景")).isFalse();
    }

    @Test
    @DisplayName("韩餐与冰淇淋绝不是饮酒场所")
    void koreanAndGelatoAreNotAlcoholVenues() {
        ItineraryNode korean = node("Moon Park", "Korean restaurant");
        korean.setSpotCategory("KOREAN_RESTAURANT");
        korean.setAlcoholInvolved(true);
        korean.setSafetyWarning("🍷 涉及饮酒");
        ItineraryNode gelato = node("Gelato Messina", "Gelato");
        gelato.setSpotCategory("DESSERT");
        gelato.setAlcoholInvolved(true);
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(korean, gelato)));

        guardrail.apply(plan, LanguageEnum.ZH);

        assertThat(korean.getAlcoholInvolved()).isFalse();
        assertThat(korean.getSafetyWarning()).isNull();
        assertThat(gelato.getAlcoholInvolved()).isFalse();
        assertThat(ItinerarySafetyGuardrail.isAlcoholVenue(korean)).isFalse();
        assertThat(ItinerarySafetyGuardrail.isAlcoholVenue(gelato)).isFalse();
    }

    private static ItineraryNode node(String name, String category) {
        ItineraryNode node = new ItineraryNode();
        node.setPoiName(name);
        node.setCategory(category);
        return node;
    }

    private static TransitOptions options() {
        TransitModeOption option = new TransitModeOption("8分钟", "1.5km", 8, 1500);
        return new TransitOptions(option, option, option);
    }
}
