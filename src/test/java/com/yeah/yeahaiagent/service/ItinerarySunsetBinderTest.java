package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.dto.SpotCandidateDto;
import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.util.GeoDistanceUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("落日时域门禁与就近检索")
class ItinerarySunsetBinderTest {

    private static final double RANDWICK_LAT = -33.9143;
    private static final double RANDWICK_LNG = 151.2417;

    @Test
    @DisplayName("14:24 结束的下午半日游不挂落日卡，并拿掉跨区的天文台山")
    void afternoonHalfDayDropsSunsetCardAndObservatoryHill() {
        ItineraryPlan plan = plan(
                node("The Ritz Randwick", RANDWICK_LAT, RANDWICK_LNG, "12:40"),
                sunsetNode("Observatory Hill Park", -33.8593, 151.2048, "14:24"));
        AtomicReference<String> query = new AtomicReference<>();
        ItinerarySunsetBinder binder = binder(q -> {
            query.set(q);
            return List.of();
        });

        binder.apply(plan, goldenWeather(), "Randwick 下午咖啡半日游", LanguageEnum.ZH);

        assertThat(plan.getSunsetEvaluation()).isNull();
        assertThat(query.get()).isNull();
        assertThat(plan.getSchedule()).extracting(ItineraryNode::getPoiName)
                .containsExactly("The Ritz Randwick")
                .doesNotContain("Observatory Hill Park");
    }

    @Test
    @DisplayName("17:10 才检索 near Randwick，8.5km 外的天文台山被 5km 硬阈值剔除")
    void eveningSearchStaysInSuburbAndDropsFarCbdLookout() {
        ItineraryPlan plan = plan(
                node("The Ritz Randwick", RANDWICK_LAT, RANDWICK_LNG, "15:40"),
                node("Randwick shops", RANDWICK_LAT, RANDWICK_LNG, "17:10"));
        AtomicReference<String> query = new AtomicReference<>();
        RealPlaceDto observatory = place("Observatory Hill Park", -33.8593, 151.2048);
        RealPlaceDto coogee = place("Coogee Beach Headland", -33.9238, 151.2579);
        ItinerarySunsetBinder binder = binder(q -> {
            query.set(q);
            return List.of(observatory, coogee);
        });

        binder.apply(plan, goldenWeather(), "傍晚在 Randwick 附近转转", LanguageEnum.ZH);

        assertThat(query.get()).isEqualTo("sunset photography spot lookout near Randwick Sydney");
        assertThat(query.get()).doesNotContain("Sydney sunset");
        assertThat(GeoDistanceUtils.calculateDistanceKm(
                RANDWICK_LAT, RANDWICK_LNG, observatory.getLatitude(), observatory.getLongitude()))
                .isGreaterThan(5.0d);
        assertThat(plan.getSunsetEvaluation()).isNotNull();
        assertThat(plan.getSunsetEvaluation().recommended()).isTrue();
        assertThat(plan.getSunsetEvaluation().recommendedSpot()).isEqualTo("Coogee Beach Headland");
        assertThat(plan.getSunsetEvaluation().recommendedSpot()).doesNotContain("Observatory");
        assertThat(plan.getSchedule()).hasSize(3);
        assertThat(plan.getSchedule().get(2).getPoiName()).isEqualTo("Coogee Beach Headland");
        assertThat(plan.getSchedule().get(2).getSunsetCandidates()).isNotEmpty();
    }

    @Test
    @DisplayName("14:52 到达但停留 2 小时，离开 16:52，追加第 5 站就近落日")
    void departureAfterGateAppendsFifthSunsetStop() {
        ItineraryNode brunch = node("Single O Surry Hills", -33.8840, 151.2130, "10:00");
        brunch.setStayDuration("1小时");
        ItineraryNode walk = node("Centennial Parklands", -33.8947, 151.2328, "11:20");
        walk.setStayDuration("1小时");
        ItineraryNode lunch = node("The Ritz Randwick", RANDWICK_LAT, RANDWICK_LNG, "12:40");
        lunch.setStayDuration("1小时");
        ItineraryNode cafe = node("Bohäus Cafe Coogee", -33.9209, 151.2555, "14:52");
        cafe.setStayDuration("2小时");
        cafe.setCategory("Cafe");
        ItineraryPlan plan = plan(brunch, walk, lunch, cafe);
        AtomicReference<String> query = new AtomicReference<>();
        ItinerarySunsetBinder binder = binder(q -> {
            query.set(q);
            return List.of(
                    place("Observatory Hill Park", -33.8593, 151.2048),
                    place("Coogee Beach Headland", -33.9238, 151.2579),
                    place("Gordons Bay Lookout", -33.9162, 151.2634)
            );
        });

        assertThat(ItinerarySunsetBinder.lastDeparture(plan)).hasToString("16:52");

        binder.apply(plan, goldenWeather(), "Coogee 海边半日游", LanguageEnum.ZH);

        assertThat(query.get()).isEqualTo("sunset photography spot lookout near Coogee Sydney");
        assertThat(plan.getSchedule()).hasSize(5);
        ItineraryNode sunset = plan.getSchedule().get(4);
        assertThat(sunset.getPoiName()).isEqualTo("Coogee Beach Headland");
        assertThat(sunset.getCategoryZh()).isEqualTo("落日观景");
        assertThat(sunset.getSunsetCandidates()).extracting(SpotCandidateDto::getName)
                .contains("Coogee Beach Headland")
                .doesNotContain("Observatory Hill Park");
        assertThat(sunset.getEstimatedArrival()).isGreaterThanOrEqualTo("16:52");
        assertThat(plan.getSunsetEvaluation().recommendedSpot()).isEqualTo("Coogee Beach Headland");
    }

    @Test
    @DisplayName("直线超过 5km 的召回直接丢掉")
    void hardDistanceCapDropsFarPlaces() {
        List<RealPlaceDto> kept = ItinerarySunsetBinder.withinMaxKm(List.of(
                place("Observatory Hill Park", -33.8593, 151.2048),
                place("Coogee Beach Headland", -33.9238, 151.2579)
        ), RANDWICK_LAT, RANDWICK_LNG);
        assertThat(kept).extracting(RealPlaceDto::getTitle)
                .containsExactly("Coogee Beach Headland")
                .doesNotContain("Observatory Hill Park");
    }

    @Test
    @DisplayName("一日游晚饭卡在黄金窗内时，落日插在晚饭前且不标行程结束")
    void fullDayInsertsSunsetBeforeDinnerAndDoesNotEndThere() {
        ItineraryNode brunch = node("Single O Surry Hills", -33.8840, 151.2130, "10:00");
        brunch.setStayDuration("1小时");
        ItineraryNode lunch = node("The Ritz Randwick", RANDWICK_LAT, RANDWICK_LNG, "12:40");
        lunch.setStayDuration("1小时");
        ItineraryNode cafe = node("Bohäus Cafe Coogee", -33.9209, 151.2555, "14:52");
        cafe.setStayDuration("2小时");
        cafe.setCategory("Cafe");
        ItineraryNode dinner = node("Cho Cho San", -33.8690, 151.2250, "17:31");
        dinner.setStayDuration("停留75分钟");
        dinner.setCategory("Fine Dining");
        dinner.setSpotCategory("DINING");
        ItineraryPlan plan = plan(brunch, lunch, cafe, dinner);
        ItinerarySunsetBinder binder = binder(q -> List.of(
                place("Coogee Beach Headland", -33.9238, 151.2579)));

        binder.apply(plan, goldenWeather(), "帮我安排悉尼一日游", LanguageEnum.ZH);

        assertThat(plan.getSchedule()).hasSize(5);
        assertThat(plan.getSchedule().get(3).getPoiName()).isEqualTo("Coogee Beach Headland");
        assertThat(plan.getSchedule().get(3).getCategoryZh()).isEqualTo("落日观景");
        assertThat(plan.getSchedule().get(3).getTransitToNext()).isNotIn("行程结束", "End of itinerary");
        assertThat(plan.getSchedule().get(4).getPoiName()).isEqualTo("Cho Cho San");
    }

    @Test
    @DisplayName("只有明确看落日的 prompt 才算用户点名")
    void sunsetIntentFromPrompt() {
        assertThat(ItinerarySunsetBinder.wantsSunset("傍晚出发，想拍落日")).isTrue();
        assertThat(ItinerarySunsetBinder.wantsSunset("golden hour at Bondi")).isTrue();
        assertThat(ItinerarySunsetBinder.wantsSunset("Randwick 下午咖啡半日游")).isFalse();
    }

    private static ItinerarySunsetBinder binder(java.util.function.Function<String, List<RealPlaceDto>> search) {
        return new ItinerarySunsetBinder(null, new SydneyPlaceLocator()) {
            @Override
            List<RealPlaceDto> searchScenic(String query) {
                return search.apply(query);
            }
        };
    }

    private static SunsetEvaluation goldenWeather() {
        return new SunsetEvaluation(true, "17:48", "17:18 - 18:03", 40, 10, "今日多云微晴", null, "tip");
    }

    private static ItineraryPlan plan(ItineraryNode... nodes) {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(nodes)));
        return plan;
    }

    private static ItineraryNode sunsetNode(String name, double lat, double lng, String arrival) {
        ItineraryNode node = node(name, lat, lng, arrival);
        node.setCategory("日落景观公园");
        node.setCategoryZh("日落景观公园");
        return node;
    }

    private static ItineraryNode node(String name, double lat, double lng, String arrival) {
        ItineraryNode node = new ItineraryNode();
        node.setPoiName(name);
        node.setLatitude(lat);
        node.setLongitude(lng);
        node.setArrivalTime(arrival);
        node.setEstimatedArrival(arrival);
        return node;
    }

    private static RealPlaceDto place(String title, double lat, double lng) {
        RealPlaceDto dto = new RealPlaceDto();
        dto.setTitle(title);
        dto.setLatitude(lat);
        dto.setLongitude(lng);
        dto.setRating(4.6d);
        return dto;
    }
}
