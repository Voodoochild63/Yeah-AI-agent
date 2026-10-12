package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.config.CacheConfig;
import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.ItineraryRefineIntent;
import com.yeah.yeahaiagent.model.dto.ItineraryRefineResponse;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.dto.TransitModeOption;
import com.yeah.yeahaiagent.model.dto.TransitOptions;
import com.yeah.yeahaiagent.model.enums.ActionType;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("行程局部打补丁")
class ItineraryRefineServiceTest {

    private static final Clock THURSDAY = Clock.fixed(
            Instant.parse("2026-09-24T04:00:00Z"), ItineraryOpeningHoursChecker.SYDNEY);

    @Test
    @DisplayName("只替换目标卡片，邻居店名不变，并重算相邻通勤")
    void patchesSingleNodeAndKeepsNeighbors() {
        ItinerarySessionService sessions = new ItinerarySessionService(new CacheConfig().cacheManager());
        ItineraryRefineService service = new ItineraryRefineService(
                sessions,
                new StubIntentParser(new ItineraryRefineIntent(
                        ActionType.REPLACE, 2, "Japanese BBQ near {suburb}", "已为您替换为附近的日料烧肉")),
                new StubSerper(),
                new ItineraryTransitPlanner(new SydneyPlaceLocator()),
                new ItineraryParkingAttacher(),
                new ItineraryOpeningHoursChecker(THURSDAY),
                new ItineraryCandidateAttacher(THURSDAY),
                new ItineraryTemporalPlanner(),
                new ItinerarySafetyGuardrail(),
                new ItineraryTimelineNormalizer()
        );

        ItineraryPlan plan = samplePlan();
        sessions.begin(plan);

        ItineraryRefineResponse response = service.refine(plan.getSessionId(), "把晚饭换成附近的日料", "zh");

        assertThat(response.getTargetIndex()).isEqualTo(2);
        assertThat(response.getAction()).isEqualTo(ActionType.REPLACE);
        assertThat(response.getReason()).contains("日料");
        assertThat(response.getSearchQuery()).contains("Surry Hills").doesNotContain("{suburb}");
        assertThat(response.getPlan().getSchedule()).hasSize(3);
        assertThat(response.getPlan().getSchedule().get(0).getPoiName()).isEqualTo("The Grounds of Alexandria");
        assertThat(response.getPlan().getSchedule().get(1).getPoiName()).isEqualTo("White Rabbit Gallery");
        assertThat(response.getPlan().getSchedule().get(2).getPoiName()).isEqualTo("Cho Cho San");
        assertThat(response.getPlan().getSchedule().get(2).getLatitude()).isEqualTo(-33.8690);
        assertThat(response.getPlan().getSchedule().get(1).getTransitOptions()).isNotNull();
        assertThat(sessions.get(plan.getSessionId()).getSchedule().get(2).getPoiName()).isEqualTo("Cho Cho San");
    }

    @Test
    @DisplayName("检索词把 {suburb} 换成街区名")
    void resolvesSuburbPlaceholder() {
        assertThat(ItineraryRefineService.resolveSearchQuery("Japanese BBQ near {suburb}", "Surry Hills"))
                .isEqualTo("Japanese BBQ near Surry Hills");
        assertThat(ItineraryRefineService.stayMinutesForNewStop(
                "gelato ice cream in Bondi", "DESSERT", com.yeah.yeahaiagent.model.enums.SpotCategory.DESSERT))
                .isBetween(20, 30);
    }

    @Test
    @DisplayName("APPEND 在日落点后插入新站，不覆盖原节点，并重算通勤与营业状态")
    void appendsAfterSunsetWithoutReplacingIt() {
        ItinerarySessionService sessions = new ItinerarySessionService(new CacheConfig().cacheManager());
        ItineraryRefineService service = new ItineraryRefineService(
                sessions,
                new StubIntentParser(new ItineraryRefineIntent(
                        ActionType.APPEND, 2, "Japanese restaurant near {suburb}", "占位", "DINING")),
                new StubSerper(),
                new ItineraryTransitPlanner(new SydneyPlaceLocator()),
                new ItineraryParkingAttacher(),
                new ItineraryOpeningHoursChecker(THURSDAY),
                new ItineraryCandidateAttacher(THURSDAY),
                new ItineraryTemporalPlanner(),
                new ItinerarySafetyGuardrail(),
                new ItineraryTimelineNormalizer()
        );

        ItineraryPlan plan = sunsetPlan();
        sessions.begin(plan);

        ItineraryRefineResponse response = service.refine(plan.getSessionId(), "看完日落还想吃日料", "zh");

        assertThat(response.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(response.getTargetIndex()).isEqualTo(3);
        assertThat(response.getReason()).isEqualTo("已为您在「Mrs Macquaries Chair」后追加了「Cho Cho San」");
        assertThat(response.getPlan().getSchedule()).hasSize(4);
        assertThat(response.getPlan().getSchedule().get(0).getPoiName()).isEqualTo("The Grounds of Alexandria");
        assertThat(response.getPlan().getSchedule().get(1).getPoiName()).isEqualTo("White Rabbit Gallery");
        assertThat(response.getPlan().getSchedule().get(2).getPoiName()).isEqualTo("Mrs Macquaries Chair");
        assertThat(response.getPlan().getSchedule().get(3).getPoiName()).isEqualTo("Cho Cho San");
        assertThat(response.getPlan().getSchedule().get(3).getStayDuration()).contains("75");
        assertThat(response.getPlan().getSchedule().get(3).getArrivalTime()).isGreaterThanOrEqualTo("18:15");
        assertThat(response.getPlan().getSchedule().get(3).getOpenStatus()).isNotBlank();
        assertThat(response.getPlan().getSchedule().get(2).getTransitOptions()).isNotNull();
        assertThat(sessions.get(plan.getSessionId()).getSchedule()).hasSize(4);
        assertThat(sessions.get(plan.getSessionId()).getSchedule().get(2).getPoiName())
                .isEqualTo("Mrs Macquaries Chair");
    }

    @Test
    @DisplayName("APPEND 散步节点检索观景而非餐厅，停留 30-45 分钟")
    void appendsWalkWithScenicStay() {
        ItinerarySessionService sessions = new ItinerarySessionService(new CacheConfig().cacheManager());
        ItineraryRefineService service = new ItineraryRefineService(
                sessions,
                new StubIntentParser(new ItineraryRefineIntent(
                        ActionType.APPEND, 2, "scenic coastal walk near {suburb}", "占位", "WALK")),
                new StubSerper(),
                new ItineraryTransitPlanner(new SydneyPlaceLocator()),
                new ItineraryParkingAttacher(),
                new ItineraryOpeningHoursChecker(THURSDAY),
                new ItineraryCandidateAttacher(THURSDAY),
                new ItineraryTemporalPlanner(),
                new ItinerarySafetyGuardrail(),
                new ItineraryTimelineNormalizer()
        );

        ItineraryPlan plan = sunsetPlan();
        sessions.begin(plan);

        ItineraryRefineResponse response = service.refine(plan.getSessionId(), "看完日落去散散步", "zh");

        assertThat(response.getAction()).isEqualTo(ActionType.APPEND);
        ItineraryNode extra = response.getPlan().getSchedule().get(3);
        assertThat(extra.getPoiName()).isIn("Barangaroo Reserve", "Royal Botanic Garden");
        assertThat(extra.getCategoryZh()).contains("散步");
        assertThat(extra.getStayDuration()).containsAnyOf("30", "40", "45");
        assertThat(extra.getSpotCategory()).isEqualTo("WALK");
    }

    @Test
    @DisplayName("4 站行程想吃 gelato 必须新增第 5 站，停留 20-30 分钟并顺延时间")
    void appendsGelatoOnFourStopPlanWithoutReplacingLast() {
        ItinerarySessionService sessions = new ItinerarySessionService(new CacheConfig().cacheManager());
        ItineraryRefineService service = new ItineraryRefineService(
                sessions,
                new ItineraryRefineIntentParser(),
                new GelatoSerper(),
                new ItineraryTransitPlanner(new SydneyPlaceLocator()),
                new ItineraryParkingAttacher(),
                new ItineraryOpeningHoursChecker(THURSDAY),
                new ItineraryCandidateAttacher(THURSDAY),
                new ItineraryTemporalPlanner(),
                new ItinerarySafetyGuardrail(),
                new ItineraryTimelineNormalizer()
        );

        ItineraryPlan plan = fourStopPlan();
        sessions.begin(plan);
        String lastName = plan.getSchedule().get(3).getPoiName();

        ItineraryRefineResponse response = service.refine(plan.getSessionId(), "想吃 gelato", "zh");

        assertThat(response.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(response.getPlan().getSchedule()).hasSize(5);
        assertThat(response.getPlan().getSchedule().get(3).getPoiName()).isEqualTo(lastName);
        ItineraryNode previous = response.getPlan().getSchedule().get(3);
        ItineraryNode gelato = response.getPlan().getSchedule().get(4);
        assertThat(gelato.getPoiName()).isEqualTo("Messina Gelato");
        assertThat(gelato.getSpotCategory()).isEqualTo("DESSERT");
        assertThat(gelato.getStayDuration()).containsAnyOf("20", "25", "30");
        assertThat(gelato.getArrivalTime()).isGreaterThanOrEqualTo(previous.getArrivalTime());
        assertThat(gelato.getOpenStatus()).isNotBlank();
        assertThat(response.getTargetIndex()).isEqualTo(4);
    }

    @Test
    @DisplayName("追加酒吧后切断自驾并给出酒驾警示")
    void appendsBarAndForbidsDriving() {
        ItinerarySessionService sessions = new ItinerarySessionService(new CacheConfig().cacheManager());
        ItineraryRefineService service = new ItineraryRefineService(
                sessions,
                new StubIntentParser(new ItineraryRefineIntent(
                        ActionType.APPEND, 2, "cocktail bar wine bar near {suburb}", "占位", "BAR")),
                new BarSerper(),
                new ItineraryTransitPlanner(new SydneyPlaceLocator()),
                new ItineraryParkingAttacher(),
                new ItineraryOpeningHoursChecker(THURSDAY),
                new ItineraryCandidateAttacher(THURSDAY),
                new ItineraryTemporalPlanner(),
                new ItinerarySafetyGuardrail(),
                new ItineraryTimelineNormalizer()
        );

        ItineraryPlan plan = sunsetPlan();
        sessions.begin(plan);

        ItineraryRefineResponse response = service.refine(plan.getSessionId(), "看完日落去酒吧", "zh");
        ItineraryNode bar = response.getPlan().getSchedule().get(3);
        assertThat(bar.getSpotCategory()).isEqualTo("BAR");
        assertThat(bar.getAlcoholInvolved()).isTrue();
        assertThat(bar.getSafetyWarning()).contains("酒驾");
        assertThat(bar.getParkingInfo() == null || !bar.getParkingInfo().hasParkingRecommendation()).isTrue();
        assertThat(response.getPlan().getSchedule().get(2).getDrivingForbidden()).isTrue();
        assertThat(response.getPlan().getSchedule().get(2).getSelectedMode()).isNotEqualTo("DRIVING");
    }

    private static final class StubIntentParser extends ItineraryRefineIntentParser {
        private final ItineraryRefineIntent intent;

        StubIntentParser(ItineraryRefineIntent intent) {
            super();
            this.intent = intent;
        }

        @Override
        public ItineraryRefineIntent parse(String message, ItineraryPlan plan, LanguageEnum language) {
            return intent;
        }
    }

    private static final class GelatoSerper extends StubSerper {
        @Override
        public List<RealPlaceDto> searchVerifiedPlaces(String query) {
            return List.of(
                    place("Messina Gelato", "Gelato ice cream", 4.6, -33.8910, 151.2500,
                            List.of("Thursday: 12:00 PM – 10:00 PM"))
            );
        }
    }

    private static final class BarSerper extends StubSerper {
        @Override
        public List<RealPlaceDto> searchVerifiedPlaces(String query) {
            return List.of(
                    place("Maybe Sammy", "Cocktail bar", 4.7, -33.8612, 151.2090,
                            List.of("Thursday: 5:00 PM – 12:00 AM"))
            );
        }
    }

    private static class StubSerper extends SerperPlacesService {
        StubSerper() {
            super(new com.yeah.yeahaiagent.config.SerperProperties(), org.springframework.web.client.RestClient.builder());
        }

        @Override
        public List<RealPlaceDto> searchVerifiedPlaces(String query) {
            return List.of(
                    place("Cuckoo Callay", "Cafe", 4.5, -33.8840, 151.2130, List.of("Thursday: 8:00 AM – 3:00 PM")),
                    place("Cho Cho San", "Japanese restaurant", 4.6, -33.8690, 151.2250,
                            List.of("Thursday: 5:00 PM – 10:00 PM"))
            );
        }

        @Override
        public List<RealPlaceDto> searchScenicPlaces(String query) {
            return List.of(
                    place("Barangaroo Reserve", "Park", 4.7, -33.8615, 151.2015,
                            List.of("Thursday: Open 24 hours")),
                    place("Royal Botanic Garden", "Park", 4.8, -33.8641, 151.2166,
                            List.of("Thursday: 7:00 AM – 8:00 PM"))
            );
        }
    }

    private static ItineraryPlan samplePlan() {
        ItineraryNode brunch = node("n1", "The Grounds of Alexandria", "Brunch", "10:00",
                -33.9107, 151.1941);
        brunch.setStayDuration("停留90分钟");
        brunch.setTransitOptions(options(8));
        brunch.setSelectedMode("WALKING");

        ItineraryNode gallery = node("n2", "White Rabbit Gallery", "Gallery", "12:00",
                -33.8866, 151.2003);
        gallery.setStayDuration("停留75分钟");
        gallery.setTransitOptions(options(25));
        gallery.setSelectedMode("DRIVING");

        ItineraryNode dinner = node("n3", "Restaurant Hubert Surry Hills", "Fine Dining", "18:30",
                -33.8678, 151.2073);
        dinner.setStayDuration("停留90分钟");

        ItineraryPlan plan = new ItineraryPlan();
        plan.setTitle("Alexandria half-day");
        plan.setSchedule(new ArrayList<>(List.of(brunch, gallery, dinner)));
        return plan;
    }

    private static ItineraryPlan fourStopPlan() {
        ItineraryNode brunch = node("n1", "The Grounds of Alexandria", "Brunch", "10:00",
                -33.9107, 151.1941);
        brunch.setStayDuration("停留90分钟");
        brunch.setTransitOptions(options(8));
        brunch.setSelectedMode("WALKING");

        ItineraryNode gallery = node("n2", "White Rabbit Gallery", "Gallery", "12:00",
                -33.8866, 151.2003);
        gallery.setStayDuration("停留75分钟");
        gallery.setTransitOptions(options(25));
        gallery.setSelectedMode("DRIVING");

        ItineraryNode walk = node("n3", "Bondi to Bronte Coastal Walk", "Walk / Scenic", "14:00",
                -33.8915, 151.2770);
        walk.setStayDuration("停留40分钟");
        walk.setSpotCategory("WALK");
        walk.setTransitOptions(options(12));
        walk.setSelectedMode("WALKING");

        ItineraryNode lookout = node("n4", "Mackenzies Point", "Scenic lookout", "15:00",
                -33.8933, 151.2806);
        lookout.setStayDuration("停留45分钟");
        lookout.setSpotCategory("SCENIC");

        ItineraryPlan plan = new ItineraryPlan();
        plan.setTitle("Bondi half-day");
        plan.setSchedule(new ArrayList<>(List.of(brunch, gallery, walk, lookout)));
        return plan;
    }

    private static ItineraryPlan sunsetPlan() {
        ItineraryNode brunch = node("n1", "The Grounds of Alexandria", "Brunch", "10:00",
                -33.9107, 151.1941);
        brunch.setStayDuration("停留90分钟");
        brunch.setTransitOptions(options(8));
        brunch.setSelectedMode("WALKING");

        ItineraryNode gallery = node("n2", "White Rabbit Gallery", "Gallery", "12:00",
                -33.8866, 151.2003);
        gallery.setStayDuration("停留75分钟");
        gallery.setTransitOptions(options(25));
        gallery.setSelectedMode("DRIVING");

        ItineraryNode sunset = node("n3", "Mrs Macquaries Chair", "Sunset lookout", "16:52",
                -33.8596, 151.2229);
        sunset.setStayDuration("45分钟");
        sunset.setCategoryZh("落日观景");
        sunset.setCategoryEn("Sunset lookout");
        sunset.setSpotCategory("SUNSET");

        ItineraryPlan plan = new ItineraryPlan();
        plan.setTitle("Harbour sunset");
        plan.setSunsetEvaluation(new com.yeah.yeahaiagent.model.dto.SunsetEvaluation(
                true, "17:41", "17:26 - 18:11", 40, 10, "今日多云微晴", "Mrs Macquaries Chair", "tip"));
        plan.setSchedule(new ArrayList<>(List.of(brunch, gallery, sunset)));
        return plan;
    }

    private static ItineraryNode node(String id, String name, String category, String arrival,
                                      double lat, double lng) {
        ItineraryNode node = new ItineraryNode();
        node.setNodeId(id);
        node.setPoiName(name);
        node.setCategory(category);
        node.setArrivalTime(arrival);
        node.setLatitude(lat);
        node.setLongitude(lng);
        return node;
    }

    private static RealPlaceDto place(String title, String category, double rating,
                                      double lat, double lng, List<String> hours) {
        RealPlaceDto dto = new RealPlaceDto();
        dto.setTitle(title);
        dto.setCategory(category);
        dto.setRating(rating);
        dto.setRatingCount(80);
        dto.setLatitude(lat);
        dto.setLongitude(lng);
        dto.setOpeningHours(hours);
        dto.setOpeningHoursText(hours.get(0));
        return dto;
    }

    private static TransitOptions options(int minutes) {
        TransitModeOption option = new TransitModeOption(minutes + "分钟", "1.0km", minutes, 1000);
        return new TransitOptions(option, option, option);
    }
}
