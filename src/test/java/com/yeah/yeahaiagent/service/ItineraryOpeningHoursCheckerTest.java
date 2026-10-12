package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.dto.TransitModeOption;
import com.yeah.yeahaiagent.model.dto.TransitOptions;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.model.enums.OpenStatus;
import com.yeah.yeahaiagent.util.OpeningHoursParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("营业时间防撞")
class ItineraryOpeningHoursCheckerTest {

    private static final Clock THURSDAY_2PM = Clock.fixed(
            Instant.parse("2026-09-24T04:00:00Z"), ItineraryOpeningHoursChecker.SYDNEY);

    @Test
    @DisplayName("15:40 抵达但 15:00 打烊 → CLOSED")
    void arriveAfterCloseIsClosed() {
        var hours = OpeningHoursParser.parseLine("Thursday: 8:00 AM – 3:00 PM");
        var collision = ItineraryOpeningHoursChecker.evaluate(LocalTime.of(15, 40), hours);
        assertThat(collision.status()).isEqualTo(OpenStatus.CLOSED);
        assertThat(collision.closesAt()).isEqualTo("15:00");
    }

    @Test
    @DisplayName("距打烊不足 30 分钟 → CLOSING_SOON")
    void closingSoonWithinHalfHour() {
        var hours = OpeningHoursParser.parseLine("Thursday: 8:00 AM – 3:00 PM");
        var collision = ItineraryOpeningHoursChecker.evaluate(LocalTime.of(14, 40), hours);
        assertThat(collision.status()).isEqualTo(OpenStatus.CLOSING_SOON);
    }

    @Test
    @DisplayName("营业区间内 → OPEN；无元数据 → UNKNOWN")
    void openAndUnknown() {
        var hours = OpeningHoursParser.parseLine("Thursday: 8:00 AM – 5:00 PM");
        assertThat(ItineraryOpeningHoursChecker.evaluate(LocalTime.of(11, 0), hours).status())
                .isEqualTo(OpenStatus.OPEN);
        assertThat(ItineraryOpeningHoursChecker.evaluate(LocalTime.of(11, 0), OpeningHoursParser.DayHours.unknown())
                .status()).isEqualTo(OpenStatus.UNKNOWN);
    }

    @Test
    @DisplayName("第一站 10:00，咖啡馆 45 分钟 + 通勤 10 分钟 = 第二站 10:55")
    void cascadesArrivalFromStayAndTransit() {
        ItineraryOpeningHoursChecker checker = new ItineraryOpeningHoursChecker(THURSDAY_2PM);
        ItineraryNode cafe = node("Brickfields", "10:00", "停留45分钟", "Cafe");
        cafe.setTransitOptions(options(10));
        cafe.setSelectedMode("WALKING");
        ItineraryNode park = node("Observatory Hill Park", null, null, "Park");

        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(cafe, park)));

        RealPlaceDto bakery = place("Brickfields", List.of("Thursday: 8:00 AM – 3:00 PM"));
        checker.apply(plan, List.of(bakery), LanguageEnum.ZH);

        assertThat(cafe.getEstimatedArrival()).isEqualTo("10:00");
        assertThat(cafe.getOpenStatus()).isEqualTo("OPEN");
        assertThat(cafe.getOpeningHoursText()).contains("15:00");
        assertThat(park.getEstimatedArrival()).isEqualTo("10:55");
        assertThat(park.getOpenStatus()).isEqualTo("OPEN");
        assertThat(park.getOpeningHoursText()).contains("开放");
    }

    @Test
    @DisplayName("Serper openingHours 对象可展平成 weekday 行")
    void flattenSerperHoursObject() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.readTree("""
                {"thursday":"8:00 AM – 3:00 PM","friday":"Closed"}
                """);
        var lines = SerperPlacesService.flattenOpeningHours(node);
        assertThat(lines).anyMatch(line -> line.toLowerCase().contains("thursday") && line.contains("3:00"));
        var hours = OpeningHoursParser.forDay(lines, java.time.DayOfWeek.THURSDAY);
        assertThat(hours.windows().get(0).close()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("Maps 风格 Monday + 窄空格 7 AM–3 PM 能解析")
    void flattenMapsHoursWithNarrowSpace() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.readTree("""
                {"Monday":"7\\u202fAM–3\\u202fPM","Thursday":"7\\u202fAM–3\\u202fPM"}
                """);
        var lines = SerperPlacesService.flattenOpeningHours(node);
        var hours = OpeningHoursParser.forDay(lines, java.time.DayOfWeek.THURSDAY);
        assertThat(hours.known()).isTrue();
        assertThat(hours.windows().get(0).open()).isEqualTo(LocalTime.of(7, 0));
        assertThat(hours.windows().get(0).close()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("迟到到打烊后的咖啡馆给出闭店风险文案")
    void lateCafeGetsClosedWarning() {
        ItineraryOpeningHoursChecker checker = new ItineraryOpeningHoursChecker(THURSDAY_2PM);
        ItineraryNode cafe = node("Brickfields Chippendale", "15:40", null, "specialty coffee");
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(cafe)));
        checker.apply(plan, List.of(place("Brickfields", List.of(
                "Monday: 8:00 AM – 3:00 PM",
                "Thursday: 8:00 AM – 3:00 PM"))), LanguageEnum.ZH);

        assertThat(cafe.getOpenStatus()).isEqualTo("CLOSED");
        assertThat(cafe.getStatusWarning()).contains("15:40").contains("15:00").contains("闭店风险");
        assertThat(cafe.getStayDuration()).contains("45");
    }

    @Test
    @DisplayName("applyFrom 只从指定下标起重算，前面节点到达时刻不变")
    void applyFromLeavesEarlierStopsUntouched() {
        ItineraryOpeningHoursChecker checker = new ItineraryOpeningHoursChecker(THURSDAY_2PM);
        ItineraryNode brunch = node("The Grounds of Alexandria", "10:00", "停留90分钟", "Brunch");
        brunch.setTransitOptions(options(15));
        brunch.setSelectedMode("DRIVING");
        ItineraryNode gallery = node("White Rabbit Gallery", "11:45", "停留60分钟", "Gallery");
        gallery.setTransitOptions(options(20));
        gallery.setSelectedMode("DRIVING");
        ItineraryNode dinner = node("Cho Cho San", "18:30", "停留90分钟", "Japanese restaurant");

        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(brunch, gallery, dinner)));

        checker.applyFrom(plan, 2, List.of(place("Cho Cho San", List.of("Thursday: 5:00 PM – 10:00 PM"))),
                LanguageEnum.ZH);

        assertThat(brunch.getArrivalTime()).isEqualTo("10:00");
        assertThat(gallery.getArrivalTime()).isEqualTo("11:45");
        assertThat(dinner.getEstimatedArrival()).isEqualTo("18:30");
        assertThat(dinner.getOpenStatus()).isEqualTo("OPEN");
    }

    private static ItineraryNode node(String name, String arrival, String stay, String category) {
        ItineraryNode node = new ItineraryNode();
        node.setPoiName(name);
        node.setArrivalTime(arrival);
        node.setStayDuration(stay);
        node.setCategory(category);
        return node;
    }

    private static RealPlaceDto place(String title, List<String> hours) {
        RealPlaceDto dto = new RealPlaceDto();
        dto.setTitle(title);
        dto.setOpeningHours(hours);
        return dto;
    }

    private static TransitOptions options(int minutes) {
        TransitModeOption option = new TransitModeOption(minutes + "分钟", "200m", minutes, 200);
        return new TransitOptions(option, option, option);
    }
}
