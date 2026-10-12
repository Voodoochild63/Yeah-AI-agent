package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.dto.SpotCandidateDto;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("节点预置 Top-3 候选")
class ItineraryCandidateAttacherTest {

    private static final Clock THURSDAY = Clock.fixed(
            Instant.parse("2026-09-24T04:00:00Z"), ItineraryOpeningHoursChecker.SYDNEY);

    @Test
    @DisplayName("当前店为 slot 0，再补 2 家同类型高分店，占用名不进备选")
    void prefetchesTopThreeWithoutOccupiedNames() {
        ItineraryCandidateAttacher attacher = new ItineraryCandidateAttacher(THURSDAY);
        ItineraryNode cafe = node("Brickfields");
        cafe.setLatitude(-33.8848);
        cafe.setLongitude(151.1996);
        cafe.setCategory("Cafe");
        ItineraryNode gallery = node("White Rabbit Gallery");
        gallery.setLatitude(-33.8866);
        gallery.setLongitude(151.2003);
        gallery.setCategory("Art gallery");

        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(cafe, gallery)));

        attacher.attach(plan, List.of(
                place("Brickfields", "Cafe", 4.6, -33.8848, 151.1996),
                place("Edition Coffee Roasters", "Cafe", 4.5, -33.8785, 151.2047),
                place("Single O Surry Hills", "Cafe", 4.4, -33.8840, 151.2130),
                place("White Rabbit Gallery", "Art gallery", 4.6, -33.8866, 151.2003),
                place("No Coords Cafe", "Cafe", 4.9, null, null)
        ), LanguageEnum.ZH);

        List<SpotCandidateDto> cafeSlots = cafe.getCandidates();
        assertThat(cafeSlots).hasSize(3);
        assertThat(cafeSlots.get(0).getName()).isEqualTo("Brickfields");
        assertThat(cafeSlots.get(0).getHighlightDesc()).isEqualTo("享受地道 Brunch");
        assertThat(cafeSlots).extracting(SpotCandidateDto::getName)
                .contains("Edition Coffee Roasters", "Single O Surry Hills")
                .doesNotContain("White Rabbit Gallery", "No Coords Cafe");
        assertThat(gallery.getCandidates().get(0).getName()).isEqualTo("White Rabbit Gallery");
        assertThat(gallery.getCandidates()).extracting(SpotCandidateDto::getName)
                .doesNotContain("Brickfields");
    }

    @Test
    @DisplayName("评分不足 4.0 或坐标非法的地点不进入候选")
    void dropsLowRatingAndInvalidCoords() {
        List<RealPlaceDto> eligible = ItineraryCandidateAttacher.eligiblePlaces(List.of(
                place("Good", "Cafe", 4.2, -33.88, 151.20),
                place("Low", "Cafe", 3.9, -33.88, 151.20),
                place("ZeroZero", "Cafe", 4.8, 0d, 0d)
        ));
        assertThat(eligible).extracting(RealPlaceDto::getTitle).containsExactly("Good");
    }

    private static ItineraryNode node(String name) {
        ItineraryNode node = new ItineraryNode();
        node.setPoiName(name);
        return node;
    }

    private static RealPlaceDto place(String title, String category, double rating, Double lat, Double lng) {
        RealPlaceDto dto = new RealPlaceDto();
        dto.setTitle(title);
        dto.setCategory(category);
        dto.setRating(rating);
        dto.setRatingCount(40);
        dto.setLatitude(lat);
        dto.setLongitude(lng);
        dto.setOpeningHours(List.of("Thursday: 8:00 AM – 3:00 PM"));
        return dto;
    }
}
