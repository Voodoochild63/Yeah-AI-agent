package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("相邻景点多模式出行 + 停车重挂")
class ItineraryTransitPlannerTest {

    private ItineraryTransitPlanner transitPlanner;
    private ItineraryParkingAttacher parkingAttacher;

    @BeforeEach
    void setUp() {
        transitPlanner = new ItineraryTransitPlanner(new SydneyPlaceLocator());
        parkingAttacher = new ItineraryParkingAttacher();
    }

    @Test
    @DisplayName("Chippendale → Bondi 默认驾车，三种模式齐全，下一站重挂停车")
    void farLegDefaultsToDriveAndPacksOptions() {
        ItineraryPlan plan = plan(
                node("White Rabbit Gallery Chippendale", "步行5分钟"),
                node("Bondi Beach", "返程"));

        transitPlanner.apply(plan, LanguageEnum.ZH);
        parkingAttacher.attach(plan, LanguageEnum.ZH);

        ItineraryNode first = plan.getSchedule().get(0);
        ItineraryNode second = plan.getSchedule().get(1);
        assertThat(first.getSelectedMode()).isEqualTo("DRIVING");
        assertThat(first.getTransitOptions()).isNotNull();
        assertThat(first.getTransitOptions().getWalking().getDurationMinutes()).isPositive();
        assertThat(first.getTransitOptions().getDriving().getDurationMinutes()).isPositive();
        assertThat(first.getTransitOptions().getTransit().getDurationMinutes()).isGreaterThanOrEqualTo(6);
        assertThat(first.getTransitToNext()).contains("驾车");
        assertThat(first.getParkingInfo().hasParkingRecommendation()).isTrue();
        assertThat(second.getParkingInfo().hasParkingRecommendation()).isTrue();
        assertThat(second.getParkingSearchKeyword()).containsIgnoringCase("Bondi");
    }

    @Test
    @DisplayName("同街区默认步行，后站不重复停车入口")
    void walkableClusterDefaultsToWalk() {
        ItineraryPlan plan = plan(
                node("White Rabbit Gallery Chippendale", "步行3分钟"),
                node("Spice Alley Chippendale", "返程"));

        transitPlanner.apply(plan, LanguageEnum.ZH);
        parkingAttacher.attach(plan, LanguageEnum.ZH);

        ItineraryNode first = plan.getSchedule().get(0);
        assertThat(first.getSelectedMode()).isEqualTo("WALKING");
        assertThat(first.getTransitOptions().getWalking()).isNotNull();
        assertThat(first.getParkingInfo().hasParkingRecommendation()).isTrue();
        assertThat(plan.getSchedule().get(1).getParkingInfo().hasParkingRecommendation()).isFalse();
    }

    @Test
    @DisplayName("英文默认驾车文案为 Drive")
    void englishDriveCopy() {
        ItineraryPlan plan = plan(
                node("Newtown", "walk 5 mins"),
                node("Bondi Beach", "return"));

        transitPlanner.apply(plan, LanguageEnum.EN);

        assertThat(plan.getSchedule().get(0).getSelectedMode()).isEqualTo("DRIVING");
        assertThat(plan.getSchedule().get(0).getTransitToNext()).startsWith("Drive");
    }

    @Test
    @DisplayName("跨州脏坐标回退为 10-15 分钟步行，不返回数万分钟")
    void implausibleDistanceFallsBackToShortWalk() {
        var options = ItineraryTransitPlanner.estimateOptions(19600d, LanguageEnum.ZH);
        assertThat(options.getWalking().getDurationMinutes()).isBetween(10, 15);
        assertThat(options.getTransit().getDurationMinutes()).isLessThanOrEqualTo(120);
        assertThat(options.getTransit().getDistanceText()).doesNotContain("24535");
        assertThat(options.getWalking().getDurationMinutes()).isLessThan(1000);
    }

    @Test
    @DisplayName("20km 内公交/步行上限 120 分钟")
    void shortLegCapsAtTwoHours() {
        var options = ItineraryTransitPlanner.estimateOptions(15d, LanguageEnum.ZH);
        assertThat(options.getWalking().getDurationMinutes()).isLessThanOrEqualTo(120);
        assertThat(options.getTransit().getDurationMinutes()).isLessThanOrEqualTo(120);
        assertThat(ItineraryTransitPlanner.clampHopMinutes(58888, 8000)).isEqualTo(12);
        assertThat(ItineraryTransitPlanner.clampTransitStops(24535)).isEqualTo(2);
    }

    @Test
    @DisplayName("步行/驾车/公交：D_road=D×1.25，4.5 / 25+2 / 等车5+20")
    void estimateFormulas() {
        var options = ItineraryTransitPlanner.estimateOptions(1.0d, LanguageEnum.ZH);
        assertThat(options.getWalking().getDurationMinutes()).isEqualTo(17);
        assertThat(options.getDriving().getDurationMinutes()).isEqualTo(5);
        assertThat(options.getTransit().getDurationMinutes()).isEqualTo(9);
        assertThat(options.getWalking().getDistanceMeters()).isEqualTo(1250);
        assertThat(options.getWalking().getDistanceText()).isEqualTo("1.3km");
        assertThat(options.getTransit().getDistanceText()).contains("站");
    }

    @Test
    @DisplayName("Brickfields → Observatory Hill 按真实坐标约 3–5km，不再是 690m")
    void brickfieldsToObservatoryHillUsesRealDistance() {
        ItineraryPlan plan = plan(
                node("Brickfields Chippendale", "步行3分钟"),
                node("Observatory Hill Park", "返程"));

        transitPlanner.apply(plan, LanguageEnum.ZH);

        ItineraryNode first = plan.getSchedule().get(0);
        assertThat(first.getLatitude()).isNotNull();
        assertThat(first.getLongitude()).isNotNull();
        assertThat(plan.getSchedule().get(1).getLatitude()).isNotNull();
        assertThat(first.getSelectedMode()).isEqualTo("DRIVING");
        assertThat(first.getTransitToNext()).contains("驾车").doesNotContain("690m");
        assertThat(first.getTransitOptions().getDriving().getDistanceMeters()).isBetween(3000, 6000);
        assertThat(first.getTransitOptions().getDriving().getDistanceText()).contains("km");
        assertThat(first.getTransitOptions().getDriving().getDurationMinutes()).isGreaterThan(3);
    }

    @Test
    @DisplayName("Serper 坐标注入后，相邻两段距离互不相同")
    void serperCoordinatesProduceDistinctLegs() {
        transitPlanner.rememberPlaces(List.of(
                place("Brickfields", -33.8848, 151.1996),
                place("Observatory Hill Park", -33.8593, 151.2048),
                place("Bondi Beach", -33.8915, 151.2767)));

        ItineraryPlan plan = plan(
                node("Brickfields", null),
                node("Observatory Hill Park", null),
                node("Bondi Beach", null));
        transitPlanner.apply(plan, LanguageEnum.ZH);

        int firstMeters = plan.getSchedule().get(0).getTransitOptions().getDriving().getDistanceMeters();
        int secondMeters = plan.getSchedule().get(1).getTransitOptions().getDriving().getDistanceMeters();
        assertThat(firstMeters).isBetween(3000, 6000);
        assertThat(secondMeters).isGreaterThan(firstMeters + 2000);
        assertThat(plan.getSchedule().get(0).getTransitToNext())
                .isNotEqualTo(plan.getSchedule().get(1).getTransitToNext());
    }

    @Test
    @DisplayName("未知地点不再回落成统一的 0.6km / 690m")
    void unknownPlacesDoNotShareFakeNearDistance() {
        ItineraryPlan plan = plan(
                node("Totally Unknown Cafe Alpha", null),
                node("Totally Unknown Park Omega", null));

        transitPlanner.apply(plan, LanguageEnum.ZH);

        assertThat(plan.getSchedule().get(0).getTransitOptions()).isNull();
        assertThat(plan.getSchedule().get(0).getTransitToNext()).isNull();
    }

    private static ItineraryPlan plan(ItineraryNode... nodes) {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(nodes)));
        return plan;
    }

    private static ItineraryNode node(String name, String transit) {
        ItineraryNode node = new ItineraryNode();
        node.setPoiName(name);
        node.setTransitToNext(transit);
        return node;
    }

    private static RealPlaceDto place(String title, double lat, double lng) {
        RealPlaceDto dto = new RealPlaceDto();
        dto.setTitle(title);
        dto.setLatitude(lat);
        dto.setLongitude(lng);
        return dto;
    }
}
