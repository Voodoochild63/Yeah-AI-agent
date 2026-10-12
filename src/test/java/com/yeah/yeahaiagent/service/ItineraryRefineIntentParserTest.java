package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.ItineraryRefineIntent;
import com.yeah.yeahaiagent.model.enums.ActionType;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("局部修改意图解析")
class ItineraryRefineIntentParserTest {

    private final ItineraryRefineIntentParser parser = new ItineraryRefineIntentParser();

    @Test
    @DisplayName("摘要包含 index / name / category / spotCategory")
    void summarizeIsLightweight() {
        String summary = ItineraryRefineIntentParser.summarize(samplePlan());
        assertThat(summary).contains("index=0")
                .contains("timeSlot=10:00")
                .contains("name=The Grounds of Alexandria")
                .contains("category=Brunch")
                .contains("index=2")
                .contains("name=Restaurant Hubert");
    }

    @Test
    @DisplayName("晚饭指向最晚的餐厅节点，检索词带 {suburb}")
    void dinnerMapsToEveningRestaurant() {
        ItineraryRefineIntent intent = parser.parse("把晚饭换成附近的日料或烤肉", samplePlan(), LanguageEnum.ZH);
        assertThat(intent.getTargetIndex()).isEqualTo(2);
        assertThat(intent.getAction()).isEqualTo(ActionType.REPLACE);
        assertThat(intent.getSearchQuery()).contains("Japanese BBQ").contains("{suburb}");
        assertThat(intent.getReason()).isNotBlank();
    }

    @Test
    @DisplayName("下午茶指向咖啡馆节点")
    void afternoonTeaMapsToCafe() {
        ItineraryRefineIntent intent = parser.parse("下午茶换成安静的咖啡馆", samplePlan(), LanguageEnum.ZH);
        assertThat(intent.getTargetIndex()).isEqualTo(1);
        assertThat(intent.getAction()).isEqualTo(ActionType.REPLACE);
        assertThat(intent.getSearchQuery().toLowerCase()).contains("cafe");
    }

    @Test
    @DisplayName("第2站按 1-based 下标定位")
    void numberedStopIsOneBased() {
        ItineraryRefineIntent intent = parser.parse("把第2站换成艺廊", samplePlan(), LanguageEnum.ZH);
        assertThat(intent.getTargetIndex()).isEqualTo(1);
        assertThat(intent.getAction()).isEqualTo(ActionType.REPLACE);
    }

    @Test
    @DisplayName("看完日落还想吃日料必须是 APPEND，锚点是日落站")
    void afterSunsetJapaneseIsAppend() {
        ItineraryRefineIntent intent = parser.parse("看完日落还想吃日料", sunsetPlan(), LanguageEnum.ZH);
        assertThat(intent.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(intent.getTargetIndex()).isEqualTo(2);
        assertThat(intent.getSearchQuery().toLowerCase()).contains("japanese");
        assertThat(intent.getCategory()).isEqualTo("DINING");
        assertThat(intent.getReason()).contains("追加");
    }

    @Test
    @DisplayName("散步/吹海风必须是 WALK，检索词是步道观景而不是餐厅")
    void walkIsScenicNotRestaurant() {
        ItineraryRefineIntent intent = parser.parse("看完日落去吹海风散散步", sunsetPlan(), LanguageEnum.ZH);
        assertThat(intent.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(intent.getCategory()).isEqualTo("WALK");
        assertThat(intent.getSearchQuery().toLowerCase())
                .contains("walk")
                .doesNotContain("restaurant")
                .doesNotContain("bbq");
    }

    @Test
    @DisplayName("看展检索艺廊，而不是餐厅")
    void exhibitionIsAttraction() {
        ItineraryRefineIntent intent = parser.parse("下午再去看展", samplePlan(), LanguageEnum.ZH);
        assertThat(intent.getCategory()).isEqualTo("ATTRACTION");
        assertThat(intent.getSearchQuery().toLowerCase()).contains("gallery").doesNotContain("restaurant");
    }

    @Test
    @DisplayName("LLM 把散步搜成餐厅时强制改回步道")
    void coerceWalkAwayFromRestaurantQuery() {
        ItineraryRefineIntent llm = new ItineraryRefineIntent(
                ActionType.APPEND, 2, "Japanese restaurant near {suburb}", "已追加餐厅", "DINING");
        ItineraryRefineIntent intent = ItineraryRefineIntentParser.coerceCategory(llm, "看完日落去散散步");
        assertThat(intent.getCategory()).isEqualTo("WALK");
        assertThat(intent.getSearchQuery().toLowerCase()).contains("walk").doesNotContain("restaurant");
    }

    @Test
    @DisplayName("散步后吃韩料：类别是韩餐，检索词不被 WALK 覆盖")
    void koreanAfterWalkIsDiningNotWalk() {
        ItineraryRefineIntent intent = parser.parse("散步后吃韩料", walkPlan(), LanguageEnum.ZH);
        assertThat(intent.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(intent.getTargetIndex()).isEqualTo(1);
        assertThat(intent.getCategory()).isEqualTo("KOREAN_RESTAURANT");
        assertThat(intent.getSearchQuery().toLowerCase())
                .contains("korean")
                .doesNotContain("walk")
                .doesNotContain("scenic");
    }

    @Test
    @DisplayName("散步前吃韩餐：插到散步节点之前")
    void koreanBeforeWalkInsertsBeforeWalk() {
        ItineraryRefineIntent intent = parser.parse("散步前吃韩餐", walkPlan(), LanguageEnum.ZH);
        assertThat(intent.getAction()).isEqualTo(ActionType.INSERT_BEFORE);
        assertThat(intent.getTargetIndex()).isEqualTo(1);
        assertThat(intent.getCategory()).isEqualTo("KOREAN_RESTAURANT");
        assertThat(intent.getSearchQuery().toLowerCase()).contains("korean restaurant");
    }

    @Test
    @DisplayName("吃 gelato 检索甜品，不被散步模板改写")
    void gelatoIsDessertQuery() {
        ItineraryRefineIntent intent = parser.parse("吃 gelato", walkPlan(), LanguageEnum.ZH);
        assertThat(intent.getCategory()).isEqualTo("DESSERT");
        assertThat(intent.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(intent.getTargetIndex()).isEqualTo(2);
        assertThat(intent.getSearchQuery().toLowerCase()).contains("gelato").contains("ice cream");
        assertThat(intent.getSearchQuery().toLowerCase()).doesNotContain("walk");
    }

    @Test
    @DisplayName("想吃 gelato 必须追加到末尾，不能覆盖现有节点")
    void wantGelatoAppendsInsteadOfReplacingLast() {
        ItineraryRefineIntent intent = parser.parse("想吃 gelato", walkPlan(), LanguageEnum.ZH);
        assertThat(intent.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(intent.getTargetIndex()).isEqualTo(2);
        assertThat(intent.getCategory()).isEqualTo("DESSERT");
        assertThat(intent.getSearchQuery().toLowerCase()).contains("gelato");
    }

    @Test
    @DisplayName("还想喝杯咖啡默认追加，而不是替换咖啡馆")
    void wantCoffeeAppendsWithoutReplaceWords() {
        ItineraryRefineIntent intent = parser.parse("还想喝杯咖啡", samplePlan(), LanguageEnum.ZH);
        assertThat(intent.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(intent.getTargetIndex()).isEqualTo(2);
        assertThat(intent.getCategory()).isEqualTo("CAFE");
    }

    @Test
    @DisplayName("想去散步默认追加到末尾")
    void wantWalkAppendsWithoutReplaceWords() {
        ItineraryRefineIntent intent = parser.parse("想去散步", samplePlan(), LanguageEnum.ZH);
        assertThat(intent.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(intent.getTargetIndex()).isEqualTo(2);
        assertThat(intent.getCategory()).isEqualTo("WALK");
    }

    @Test
    @DisplayName("LLM 把想吃 gelato 误判成 REPLACE 时仍强制追加末站")
    void coerceLlmGelatoReplaceIntoAppend() {
        ItineraryRefineIntent llm = new ItineraryRefineIntent(
                ActionType.REPLACE, 2, "gelato ice cream in Bondi", "已为您替换最后一站", "DESSERT");
        ItineraryRefineIntent intent = ItineraryRefineIntentParser.coerceAppend(
                llm, "想吃 gelato", walkPlan(), LanguageEnum.ZH);
        assertThat(intent.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(intent.getTargetIndex()).isEqualTo(2);
        assertThat(intent.getReason()).contains("追加").doesNotContain("替换");
    }

    @Test
    @DisplayName("LLM 已给出 Korean restaurant in Bondi 时后端不得篡改 searchQuery")
    void keepsLlmKoreanSearchQuery() {
        ItineraryRefineIntent llm = new ItineraryRefineIntent(
                ActionType.APPEND, 1, "Korean restaurant in Bondi", "加韩餐", "KOREAN_RESTAURANT");
        ItineraryRefineIntent intent = ItineraryRefineIntentParser.coerceCategory(llm, "散步后吃韩餐");
        assertThat(intent.getCategory()).isEqualTo("KOREAN_RESTAURANT");
        assertThat(intent.getSearchQuery()).isEqualTo("Korean restaurant in Bondi");
    }

    @Test
    @DisplayName("在最后加一顿晚餐是 APPEND，锚点为时间线末尾")
    void addDinnerAtEndIsAppend() {
        ItineraryRefineIntent intent = parser.parse("在最后加一顿晚餐", sunsetPlan(), LanguageEnum.ZH);
        assertThat(intent.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(intent.getTargetIndex()).isEqualTo(2);
        assertThat(intent.getSearchQuery().toLowerCase()).contains("restaurant");
    }

    @Test
    @DisplayName("LLM 误判 REPLACE 时仍强制 APPEND，避免覆盖日落")
    void coerceLlmReplaceIntoAppend() {
        ItineraryRefineIntent llm = new ItineraryRefineIntent(
                ActionType.REPLACE, 2, "Japanese restaurant near {suburb}", "已为您替换日落点");
        ItineraryRefineIntent intent = ItineraryRefineIntentParser.coerceAppend(
                llm, "看完日落还想吃日料", sunsetPlan(), LanguageEnum.ZH);
        assertThat(intent.getAction()).isEqualTo(ActionType.APPEND);
        assertThat(intent.getTargetIndex()).isEqualTo(2);
        assertThat(intent.getReason()).contains("追加").doesNotContain("替换");
    }

    private static ItineraryPlan samplePlan() {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(
                node("n1", "The Grounds of Alexandria", "Brunch", "10:00"),
                node("n2", "White Rabbit Gallery Cafe", "Cafe", "14:00"),
                node("n3", "Restaurant Hubert", "Fine Dining", "18:30")
        )));
        return plan;
    }

    private static ItineraryPlan walkPlan() {
        ItineraryPlan plan = new ItineraryPlan();
        ItineraryNode walk = node("n2", "Bondi to Bronte Coastal Walk", "Walk / Scenic", "15:30");
        walk.setSpotCategory("WALK");
        plan.setSchedule(new ArrayList<>(List.of(
                node("n1", "The Grounds of Alexandria", "Brunch", "10:00"),
                walk,
                node("n3", "Mrs Macquaries Chair", "Sunset lookout", "17:30")
        )));
        return plan;
    }

    private static ItineraryPlan sunsetPlan() {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setSchedule(new ArrayList<>(List.of(
                node("n1", "The Grounds of Alexandria", "Brunch", "10:00"),
                node("n2", "White Rabbit Gallery", "Gallery", "13:00"),
                node("n3", "Mrs Macquaries Chair", "Sunset lookout", "17:30")
        )));
        return plan;
    }

    private static ItineraryNode node(String id, String name, String category, String arrival) {
        ItineraryNode node = new ItineraryNode();
        node.setNodeId(id);
        node.setPoiName(name);
        node.setCategory(category);
        node.setArrivalTime(arrival);
        return node;
    }
}
