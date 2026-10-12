package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.ItineraryRefineIntent;
import com.yeah.yeahaiagent.model.enums.ActionType;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.model.enums.SpotCategory;
import com.yeah.yeahaiagent.resilience.UpstreamApiGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从当前行程摘要 + 用户自然语言中解析「替换还是追加、锚点在哪、去搜什么」。
 * 优先走 Spring AI 结构化输出；失败时回落到关键词启发式，保证局部修改链路可测、可降级。
 */
@Service
@Slf4j
public class ItineraryRefineIntentParser {

    private static final Pattern STOP_INDEX = Pattern.compile(
            "(?:第\\s*([0-9一二三四五六七八九十]+)\\s*(?:站|个)|stop\\s*#?\\s*(\\d+))",
            Pattern.CASE_INSENSITIVE);

    private final ChatClient chatClient;
    private final UpstreamApiGuard upstreamApiGuard;

    public ItineraryRefineIntentParser(ChatModel dashscopeChatModel) {
        this(dashscopeChatModel, null);
    }

    @Autowired
    public ItineraryRefineIntentParser(ChatModel dashscopeChatModel,
                                       ObjectProvider<UpstreamApiGuard> upstreamApiGuard) {
        this.chatClient = dashscopeChatModel == null ? null : ChatClient.builder(dashscopeChatModel)
                .defaultTemplateRenderer((template, variables) -> template)
                .build();
        this.upstreamApiGuard = upstreamApiGuard == null ? null : upstreamApiGuard.getIfAvailable();
    }

    ItineraryRefineIntentParser() {
        this.chatClient = null;
        this.upstreamApiGuard = null;
    }

    public ItineraryRefineIntent parse(String message, ItineraryPlan plan, LanguageEnum language) {
        String summary = summarize(plan);
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        ItineraryRefineIntent intent = bindSemantics(
                parseWithLlm(message, summary, lang), message, plan, lang);
        if (isUsable(intent, plan)) {
            return intent;
        }
        log.info("refine intent LLM missed, using heuristic. message={}", message);
        return fallbackParse(message, plan, lang);
    }

    static String summarize(ItineraryPlan plan) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return "(empty schedule)";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < schedule.size(); i++) {
            ItineraryNode node = schedule.get(i);
            if (node == null) {
                continue;
            }
            String timeSlot = firstFilled(node.getArrivalTime(), node.getEstimatedArrival(), "-");
            String venue = firstFilled(node.getPoiName(), "-");
            String category = firstFilled(node.getCategory(), node.getCategoryZh(), node.getCategoryEn(), "-");
            builder.append("index=").append(i)
                    .append(" | name=").append(venue)
                    .append(" | category=").append(category)
                    .append(" | spotCategory=").append(firstFilled(node.getSpotCategory(), "-"))
                    .append(" | timeSlot=").append(timeSlot)
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private ItineraryRefineIntent parseWithLlm(String message, String summary, LanguageEnum language) {
        if (chatClient == null || message == null || message.isBlank()) {
            return null;
        }
        try {
            if (upstreamApiGuard != null) {
                upstreamApiGuard.acquireLlm();
            }
            ItineraryRefineIntent intent = chatClient.prompt()
                    .templateRenderer((template, variables) -> template)
                    .system("""
                            You patch an existing Sydney itinerary with a single local change.
                            Read the stop list (index, name, category, spotCategory) and the user's request.
                            Return ONLY a JSON object with keys action, targetIndex, searchQuery, reason, category.
                            action must be REPLACE, APPEND, INSERT_BEFORE or DELETE.
                            category is the payload the user wants to search, for example:
                            WALK, SCENIC, PARK, ATTRACTION, BAR, PUB, DESSERT, SHOPPING, KOREAN_RESTAURANT, DINING, CAFE, TEA, SUNSET.
                            Rules:
                            1. REPLACE: targetIndex is the 0-based index of the ONE existing card to overwrite.
                            2. APPEND: insert AFTER targetIndex. Use the walk/sunset/named stop as the anchor when the user says 之后/后/after.
                            3. INSERT_BEFORE: insert BEFORE targetIndex. Use this when the user says 之前/前/before the walk (散步前吃韩餐).
                            4. DELETE: remove the stop at targetIndex.
                            5. searchQuery MUST faithfully encode the user's cuisine or place type as a short English Google Maps phrase.
                               Examples: 吃韩餐 / 吃韩料 -> "Korean restaurant in {suburb}"; 吃 gelato -> "gelato ice cream in {suburb}".
                               NEVER replace a food request with a walk/park/restaurant-generic template. Do not rewrite a correct searchQuery.
                            6. If 散步/walk/stroll is only a TIME ANCHOR (散步后吃韩料, 散步前吃韩餐, after the walk get gelato), category is the FOOD (KOREAN_RESTAURANT / DESSERT / DINING), NOT WALK. Locate the WALK/SCENIC/PARK stop in the list for targetIndex.
                            7. Only set category=WALK when the user actually wants a walk/stroll as the new or replaced stop.
                            8. reason is one sentence in the request language.
                            10. APPEND is the default. If the user states a NEW desire without naming a card to overwrite
                               (想吃 gelato / 还想喝杯咖啡 / 想去散步 / I want gelato / add a coffee), you MUST APPEND.
                               targetIndex is the last stop unless they named another anchor (散步后 / after sunset).
                               NEVER REPLACE an existing card unless they explicitly say 换成 / 改成 / 替换 / 不要…改吃 / swap / replace / change to.
                            11. Do not refuse APPEND because the itinerary already has 3, 4 or 5 stops. Extra dessert/coffee/walk cards are allowed until about 22:00.
                            """)
                    .user("""
                            lang=__LANG__

                            Current stops (index / name / category / spotCategory):
                            __SUMMARY__

                            User request:
                            __MESSAGE__
                            """
                            .replace("__LANG__", language.getCode())
                            .replace("__SUMMARY__", summary)
                            .replace("__MESSAGE__", message.trim()))
                    .call()
                    .entity(ItineraryRefineIntent.class);
            return intent;
        } catch (RuntimeException ex) {
            log.warn("refine intent LLM call failed, falling back", ex);
            return null;
        }
    }

    static ItineraryRefineIntent fallbackParse(String message, ItineraryPlan plan, LanguageEnum language) {
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        int size = schedule == null ? 0 : schedule.size();
        SpotCategory payload = payloadCategory(message);
        SemanticAnchor anchor = resolveSemanticAnchor(message, schedule, payload);
        int index = anchor.targetIndex();
        if (index < 0 || index >= size) {
            index = Math.max(0, size - 1);
        }
        String query = fallbackSearchQuery(message, payload);
        ItineraryNode target = schedule == null || schedule.isEmpty() ? null : schedule.get(index);
        ActionType action = anchor.action();
        String reason = buildReason(action, target, lang);
        return new ItineraryRefineIntent(action, index, query, reason, payload.name());
    }

    /**
     * 把 LLM 结果与句子里的语义锚点对齐：不覆盖已经正确的 searchQuery，也不把「散步后吃韩料」锁成 WALK。
     */
    static ItineraryRefineIntent bindSemantics(ItineraryRefineIntent intent,
                                               String message,
                                               ItineraryPlan plan,
                                               LanguageEnum language) {
        if (intent == null) {
            return null;
        }
        SpotCategory payload = payloadCategory(message);
        SpotCategory fromField = SpotCategory.from(intent.getCategory());
        SpotCategory resolved = payload != SpotCategory.OTHER ? payload
                : (fromField != SpotCategory.OTHER ? fromField : SpotCategory.OTHER);
        intent.setCategory(resolved.name());
        intent.setSearchQuery(preferSearchQuery(intent.getSearchQuery(), message, resolved));

        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return intent;
        }
        SemanticAnchor anchor = resolveSemanticAnchor(message, schedule, resolved);
        if (anchor.forced() || intent.getAction() == null) {
            intent.setAction(anchor.action());
        }
        if (anchor.forced() || intent.getTargetIndex() == null) {
            intent.setTargetIndex(anchor.targetIndex());
        }
        if (!looksLikeReplace(message) && !looksLikeDelete(message) && !looksLikeBeforeAnchor(message)
                && (intent.resolvedAction() == ActionType.REPLACE
                || looksLikeInsert(message)
                || looksLikeNewDesire(message, resolved))) {
            intent.setAction(ActionType.APPEND);
            intent.setTargetIndex(anchor.action() == ActionType.APPEND ? anchor.targetIndex() : lastIndex(schedule));
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        int size = schedule.size();
        if (intent.getTargetIndex() != null && size > 0 && intent.getTargetIndex() >= size) {
            intent.setTargetIndex(size - 1);
        }
        ItineraryNode target = schedule == null || intent.getTargetIndex() == null
                || intent.getTargetIndex() < 0 || intent.getTargetIndex() >= size
                ? null
                : schedule.get(intent.getTargetIndex());
        if (intent.getReason() == null || intent.getReason().isBlank()
                || (intent.resolvedAction().inserts()
                && (intent.getReason().contains("替换")
                || intent.getReason().toLowerCase(Locale.ROOT).contains("replac")))) {
            intent.setReason(buildReason(intent.resolvedAction(), target, lang));
        }
        return intent;
    }

    /** 兼容旧测试名：纠正被散步词污染的类别，但绝不覆盖已经正确的餐饮检索词。 */
    static ItineraryRefineIntent coerceCategory(ItineraryRefineIntent intent, String message) {
        return bindSemantics(intent, message, null, LanguageEnum.ZH);
    }

    static ItineraryRefineIntent coerceAppend(ItineraryRefineIntent intent,
                                              String message,
                                              ItineraryPlan plan,
                                              LanguageEnum language) {
        return bindSemantics(intent, message, plan, language);
    }

    record SemanticAnchor(ActionType action, int targetIndex, boolean forced) {
        SemanticAnchor(ActionType action, int targetIndex) {
            this(action, targetIndex, true);
        }
    }

    static SemanticAnchor resolveSemanticAnchor(String message, List<ItineraryNode> schedule, SpotCategory payload) {
        if (schedule == null || schedule.isEmpty()) {
            return new SemanticAnchor(ActionType.REPLACE, 0);
        }
        if (looksLikeDelete(message)) {
            int named = namedVenue(message, schedule);
            int walk = indexOfWalk(schedule);
            int index = named >= 0 ? named : (walk >= 0 && SpotCategory.looksLikeWalk(lower(message)) ? walk : lastIndex(schedule));
            return new SemanticAnchor(ActionType.DELETE, index);
        }
        int named = namedVenue(message, schedule);
        int walk = indexOfWalk(schedule);
        int sunset = indexOfCategory(schedule, SpotCategory.SUNSET);
        if (looksLikeBeforeAnchor(message)) {
            int anchor = named >= 0 ? named : (walk >= 0 ? walk : 0);
            return new SemanticAnchor(ActionType.INSERT_BEFORE, anchor);
        }
        if (looksLikeReplace(message)) {
            return new SemanticAnchor(ActionType.REPLACE, resolveIndex(message, schedule));
        }
        if (looksLikeAfterAnchor(message) || looksLikeAppend(message) || looksLikeNewDesire(message, payload)) {
            int anchor = named >= 0 ? named
                    : (containsSunset(message) && sunset >= 0 ? sunset
                    : (walk >= 0 && containsWalk(message) ? walk : lastIndex(schedule)));
            return new SemanticAnchor(ActionType.APPEND, anchor);
        }
        return new SemanticAnchor(ActionType.APPEND, lastIndex(schedule));
    }

    static SpotCategory payloadCategory(String message) {
        String blob = lower(message);
        if (SpotCategory.looksLikeKorean(blob)) {
            return SpotCategory.KOREAN_RESTAURANT;
        }
        if (SpotCategory.looksLikeDessert(blob)) {
            return SpotCategory.DESSERT;
        }
        if ((containsAny(blob, "酒吧", "清吧", "酒馆", "夜店")
                || (SpotCategory.looksLikeBar(blob) && !SpotCategory.looksLikeEdiblePayload(blob)))
                && !SpotCategory.looksLikeKorean(blob)) {
            return containsAny(blob, "pub", "酒馆") ? SpotCategory.PUB : SpotCategory.BAR;
        }
        if (containsAny(blob, "看展", "艺廊", "gallery", "exhibition", "美术")) {
            return SpotCategory.ATTRACTION;
        }
        if (containsAny(blob, "afternoon tea", "high tea", "下午茶") && !containsAny(blob, "换成", "改成", "cafe", "咖啡")) {
            return SpotCategory.TEA;
        }
        if (containsAny(blob, "咖啡", "咖啡馆", "cafe", "coffee") && !containsAny(blob, "晚饭", "晚餐", "dinner")) {
            return SpotCategory.CAFE;
        }
        if (containsAny(blob, "日料", "japanese", "sushi", "ramen", "晚饭", "晚餐", "dinner", "午餐", "lunch", "烤肉")) {
            return SpotCategory.DINING;
        }
        if (SpotCategory.looksLikeWalk(blob) && !SpotCategory.looksLikeEdiblePayload(blob)) {
            return SpotCategory.WALK;
        }
        SpotCategory fromText = SpotCategory.fromText(message);
        return fromText == SpotCategory.OTHER && SpotCategory.looksLikeEdiblePayload(blob)
                ? SpotCategory.DINING
                : fromText;
    }

    static String preferSearchQuery(String llmQuery, String message, SpotCategory payload) {
        String query = llmQuery == null ? "" : llmQuery.trim();
        if (!query.isBlank() && queryFitsPayload(query, payload)) {
            return query;
        }
        if (!query.isBlank() && payload != SpotCategory.WALK && payload != SpotCategory.SCENIC
                && payload != SpotCategory.PARK && !queryLooksLikeWalkOnly(query)) {
            return query;
        }
        return fallbackSearchQuery(message, payload);
    }

    static boolean queryFitsPayload(String query, SpotCategory payload) {
        String lower = query.toLowerCase(Locale.ROOT);
        return switch (payload) {
            case KOREAN_RESTAURANT -> lower.contains("korean") || lower.contains("한");
            case DESSERT -> containsAny(lower, "gelato", "ice cream", "dessert", "sweets");
            case WALK, SCENIC, PARK -> queryLooksLikeWalkOnly(lower);
            case ATTRACTION -> containsAny(lower, "gallery", "museum", "exhibition");
            case BAR, PUB -> containsAny(lower, "bar", "pub", "brewery");
            case CAFE -> containsAny(lower, "cafe", "coffee");
            case TEA -> containsAny(lower, "tea");
            case DINING -> containsAny(lower, "restaurant", "japanese", "korean", "bbq", "dining");
            default -> true;
        };
    }

    static boolean queryLooksLikeWalkOnly(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "restaurant", "korean", "gelato", "bbq", "cafe", "dining")) {
            return false;
        }
        return containsAny(lower, "walk", "stroll", "lookout", "reserve", "coastal", "park", "promenade");
    }

    static int resolveIndex(String message, List<ItineraryNode> schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return 0;
        }
        String text = message == null ? "" : message.trim();
        Integer numbered = numberedStop(text);
        if (numbered != null && numbered >= 0 && numbered < schedule.size()) {
            return numbered;
        }
        int named = namedVenue(text, schedule);
        if (named >= 0) {
            return named;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "晚饭", "晚餐", "宵夜", "dinner", "supper", "evening meal")) {
            return bestMatch(schedule, "dinner", "dining", "restaurant", "fine dining", "bar", "wine",
                    "晚饭", "晚餐", "餐厅", "日料", "烤肉");
        }
        if (containsAny(lower, "下午茶", "茶", "afternoon tea", "high tea")) {
            return bestMatch(schedule, "tea", "cafe", "coffee", "patisserie", "afternoon", "下午茶", "茶");
        }
        if (containsAny(lower, "咖啡", "咖啡馆", "cafe", "coffee", "quiet cafe")) {
            return bestMatch(schedule, "cafe", "coffee", "brunch", "bakery", "咖啡");
        }
        if (containsAny(lower, "午餐", "午饭", "lunch")) {
            return bestMatch(schedule, "lunch", "brunch", "cafe", "午餐");
        }
        if (containsAny(lower, "早午餐", "早餐", "brunch", "breakfast")) {
            return bestMatch(schedule, "brunch", "breakfast", "cafe", "coffee", "早午餐", "早餐");
        }
        if (containsAny(lower, "落日", "日落", "sunset", "golden hour")) {
            return bestMatch(schedule, "sunset", "lookout", "harbour", "beach", "落日", "观景");
        }
        return lastIndex(schedule);
    }

    static String resolveSearchQuery(String message) {
        return fallbackSearchQuery(message, payloadCategory(message));
    }

    static String resolveSearchQuery(String message, SpotCategory category) {
        return fallbackSearchQuery(message, category == null ? payloadCategory(message) : category);
    }

    static String fallbackSearchQuery(String message, SpotCategory payload) {
        SpotCategory resolved = payload == null || payload == SpotCategory.OTHER
                ? payloadCategory(message) : payload;
        String lower = lower(message);
        if (resolved == SpotCategory.KOREAN_RESTAURANT || SpotCategory.looksLikeKorean(lower)) {
            return "Korean restaurant in {suburb}";
        }
        if (resolved == SpotCategory.DESSERT || SpotCategory.looksLikeDessert(lower)) {
            return containsAny(lower, "gelato")
                    ? "gelato ice cream in {suburb}"
                    : "dessert cafe gelato in {suburb}";
        }
        if (resolved.scenicWalk() && !SpotCategory.looksLikeEdiblePayload(lower)) {
            if (containsAny(lower, "circular quay", "harbour", "harbor", "海港", "barangaroo")) {
                return "Barangaroo Reserve night walk";
            }
            if (containsAny(lower, "海风", "海边", "coast", "sea breeze", "吹海风")) {
                return "scenic coastal walk near {suburb}";
            }
            return "scenic coastal walk lookout near {suburb}";
        }
        if (resolved == SpotCategory.ATTRACTION || containsAny(lower, "gallery", "展", "美术", "艺廊", "看展")) {
            return "art gallery museum near {suburb}";
        }
        if (resolved == SpotCategory.BAR || resolved == SpotCategory.PUB) {
            return "cocktail bar wine bar near {suburb}";
        }
        if (resolved == SpotCategory.SHOPPING) {
            return "independent boutique market near {suburb}";
        }
        if (containsAny(lower, "日料", "日本料理", "japanese", "sushi", "ramen", "izakaya", "yakiniku")) {
            if (containsAny(lower, "烤肉", "bbq", "yakiniku")) {
                return "Japanese BBQ near {suburb}";
            }
            return "Japanese restaurant near {suburb}";
        }
        if (containsAny(lower, "烤肉", "bbq", "barbecue", "yakiniku")) {
            return "Korean BBQ near {suburb}";
        }
        if (containsAny(lower, "安静", "quiet", "calm") && containsAny(lower, "咖啡", "cafe", "coffee")) {
            return "quiet specialty cafe near {suburb}";
        }
        if (resolved == SpotCategory.TEA || containsAny(lower, "afternoon tea", "high tea")) {
            return "afternoon tea cafe near {suburb}";
        }
        if (resolved == SpotCategory.CAFE || containsAny(lower, "咖啡", "咖啡馆", "cafe", "coffee")) {
            return "specialty coffee near {suburb}";
        }
        if (containsAny(lower, "brunch", "早午餐")) {
            return "brunch cafe near {suburb}";
        }
        String extracted = extractAfterSwap(message);
        if (extracted != null && resolved.longDining()) {
            return extracted + " near {suburb}";
        }
        if (resolved.longDining() || SpotCategory.looksLikeDining(lower)) {
            return "highly rated restaurant near {suburb}";
        }
        if (extracted != null) {
            return extracted + " near {suburb}";
        }
        return "highly rated place near {suburb}";
    }

    static boolean looksLikeAppend(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "还想", "还想去", "还想吃", "想吃", "想喝", "想去", "想要", "想来",
                "再加", "再加上", "再去", "再吃", "再喝", "追加", "后面加", "在后面", "在最后",
                "加一顿", "加一个", "加一家", "加一站", "加个", "看完", "结束后", "完了再",
                "散步后", "之后再", "之后吃",
                "also want", "also like", "add a", "add an", "add one", "add dinner", "add lunch",
                "afterwards", "after that", "after watching", "after sunset", "after the walk",
                "at the end", "plus a", "i want", "i'd like", "id like", "wanna", "get some", "have some");
    }

    /**
     * 用户提出新的探索诉求，但没有点名要换掉哪一张卡。
     */
    static boolean looksLikeNewDesire(String message, SpotCategory payload) {
        if (looksLikeReplace(message) || looksLikeDelete(message)) {
            return false;
        }
        String lower = lower(message);
        if (containsAny(lower, "想吃", "想喝", "想去", "想要", "想来", "来份", "来个", "来点",
                "吃 gelato", "吃冰淇淋", "吃甜品")) {
            return true;
        }
        return payload == SpotCategory.DESSERT
                || payload == SpotCategory.CAFE
                || payload == SpotCategory.WALK
                || payload == SpotCategory.BAR
                || payload == SpotCategory.PUB
                || payload == SpotCategory.ATTRACTION
                || SpotCategory.looksLikeEdiblePayload(lower);
    }

    static boolean looksLikeInsert(String message) {
        return looksLikeAppend(message) || looksLikeBeforeAnchor(message) || looksLikeAfterAnchor(message);
    }

    static boolean looksLikeBeforeAnchor(String message) {
        String lower = lower(message);
        return containsAny(lower, "散步前", "之前吃", "之前先", "前先吃", "前吃",
                "before the walk", "before walking", "before the stroll", "before 散步");
    }

    static boolean looksLikeAfterAnchor(String message) {
        String lower = lower(message);
        return containsAny(lower, "散步后", "之后吃", "后吃", "完再吃",
                "after the walk", "after walking", "after the stroll", "after 散步");
    }

    static boolean looksLikeDelete(String message) {
        String lower = lower(message);
        return containsAny(lower, "删掉", "删除", "去掉", "移除", "不要了", "delete", "remove");
    }

    static boolean containsWalk(String message) {
        return SpotCategory.looksLikeWalk(lower(message));
    }

    static boolean containsSunset(String message) {
        return containsAny(lower(message), "落日", "日落", "sunset", "golden hour");
    }

    static int indexOfWalk(List<ItineraryNode> schedule) {
        if (schedule == null) {
            return -1;
        }
        for (int i = 0; i < schedule.size(); i++) {
            ItineraryNode node = schedule.get(i);
            SpotCategory category = SpotCategory.from(node == null ? null : node.getSpotCategory());
            if (category.scenicWalk()) {
                return i;
            }
            String blob = node == null ? "" : (nullToEmpty(node.getCategory()) + " "
                    + nullToEmpty(node.getCategoryZh()) + " " + nullToEmpty(node.getPoiName())).toLowerCase(Locale.ROOT);
            if (SpotCategory.looksLikeWalk(blob) && !SpotCategory.looksLikeDining(blob)) {
                return i;
            }
        }
        return -1;
    }

    static int indexOfCategory(List<ItineraryNode> schedule, SpotCategory wanted) {
        if (schedule == null || wanted == null) {
            return -1;
        }
        for (int i = 0; i < schedule.size(); i++) {
            ItineraryNode node = schedule.get(i);
            if (wanted == SpotCategory.from(node == null ? null : node.getSpotCategory())
                    || (wanted == SpotCategory.SUNSET && ItineraryTemporalPlanner.isSunsetNode(node))) {
                return i;
            }
        }
        return -1;
    }

    static String lower(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    static boolean looksLikeReplace(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "换成", "改成", "替换", "换掉", "换一家", "换一个", "改吃", "改喝",
                "不要吃", "不要喝", "不要了", "swap", "replace", "change to", "change it to");
    }

    static boolean looksLikeEndAppend(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "在最后", "最后加", "末尾", "行程结尾", "at the end", "at the last", "end of");
    }

    static int resolveAppendAnchor(String message, List<ItineraryNode> schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return 0;
        }
        if (looksLikeEndAppend(message)) {
            return lastIndex(schedule);
        }
        Integer numbered = numberedStop(message == null ? "" : message.trim());
        if (numbered != null && numbered >= 0 && numbered < schedule.size()) {
            return numbered;
        }
        int named = namedVenue(message, schedule);
        if (named >= 0) {
            return named;
        }
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "落日", "日落", "sunset", "golden hour")) {
            return bestMatch(schedule, "sunset", "lookout", "harbour", "beach", "落日", "观景");
        }
        return lastIndex(schedule);
    }

    private static String buildReason(ActionType action, ItineraryNode target, LanguageEnum language) {
        String venue = target == null || target.getPoiName() == null ? "" : target.getPoiName();
        if (action == ActionType.APPEND) {
            if (language.isZh()) {
                return venue.isBlank()
                        ? "已为您追加新的一站"
                        : "已为您在" + venue + "后追加新的一站";
            }
            return venue.isBlank()
                    ? "Added a new stop to your itinerary."
                    : "Added a new stop after " + venue + ".";
        }
        if (action == ActionType.INSERT_BEFORE) {
            if (language.isZh()) {
                return venue.isBlank()
                        ? "已为您在锚点前插入新的一站"
                        : "已为您在「" + venue + "」前插入新的一站";
            }
            return venue.isBlank()
                    ? "Inserted a new stop before the matching card."
                    : "Inserted a new stop before " + venue + ".";
        }
        if (action == ActionType.DELETE) {
            if (language.isZh()) {
                return venue.isBlank() ? "已删除对应行程卡片" : "已删除「" + venue + "」";
            }
            return venue.isBlank() ? "Removed that stop." : "Removed " + venue + ".";
        }
        if (language.isZh()) {
            return venue.isBlank()
                    ? "已按你的要求替换对应行程卡片"
                    : "已为您替换「" + venue + "」这一站";
        }
        return venue.isBlank()
                ? "Updated the matching stop on your itinerary."
                : "Replaced " + venue + " with a nearby alternative.";
    }

    private static Integer numberedStop(String text) {
        Matcher matcher = STOP_INDEX.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String chinese = matcher.group(1);
        String arabic = matcher.group(2);
        int value = arabic != null ? Integer.parseInt(arabic) : parseChineseNumeral(chinese);
        if (value <= 0) {
            return null;
        }
        return value - 1;
    }

    private static int parseChineseNumeral(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(raw);
        }
        return switch (raw.trim()) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> -1;
        };
    }

    private static int namedVenue(String message, List<ItineraryNode> schedule) {
        if (message == null) {
            return -1;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < schedule.size(); i++) {
            ItineraryNode node = schedule.get(i);
            if (node == null || node.getPoiName() == null || node.getPoiName().isBlank()) {
                continue;
            }
            String name = node.getPoiName().trim().toLowerCase(Locale.ROOT);
            if (name.length() >= 4 && lower.contains(name)) {
                return i;
            }
            int score = 0;
            for (String token : name.split("[^a-z0-9\\u4e00-\\u9fff]+")) {
                if (token.length() >= 4 && lower.contains(token)) {
                    score += token.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return bestScore >= 4 ? best : -1;
    }

    private static int bestMatch(List<ItineraryNode> schedule, String... needles) {
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < schedule.size(); i++) {
            int score = categoryScore(schedule.get(i), needles) + timeSlotBonus(schedule.get(i), needles);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best >= 0 && bestScore > 0) {
            return best;
        }
        if (containsAny(String.join(" ", needles).toLowerCase(Locale.ROOT), "dinner", "晚饭", "晚餐")) {
            return lastIndex(schedule);
        }
        return lastIndex(schedule);
    }

    private static int categoryScore(ItineraryNode node, String... needles) {
        if (node == null) {
            return 0;
        }
        String blob = (nullToEmpty(node.getCategory()) + " "
                + nullToEmpty(node.getCategoryZh()) + " "
                + nullToEmpty(node.getCategoryEn()) + " "
                + nullToEmpty(node.getPoiName())).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String needle : needles) {
            if (needle != null && blob.contains(needle.toLowerCase(Locale.ROOT))) {
                score += 3;
            }
        }
        return score;
    }

    private static int timeSlotBonus(ItineraryNode node, String... needles) {
        String blob = String.join(" ", needles).toLowerCase(Locale.ROOT);
        String clock = firstFilled(node == null ? null : node.getArrivalTime(),
                node == null ? null : node.getEstimatedArrival(), "");
        int hour = parseHour(clock);
        if (hour < 0) {
            return 0;
        }
        if (containsAny(blob, "dinner", "晚饭", "晚餐", "宵夜") && hour >= 16) {
            return 2;
        }
        if (containsAny(blob, "afternoon", "下午茶", "tea") && hour >= 13 && hour < 17) {
            return 2;
        }
        if (containsAny(blob, "lunch", "午餐") && hour >= 11 && hour < 15) {
            return 2;
        }
        if (containsAny(blob, "brunch", "breakfast", "早午", "早餐") && hour < 12) {
            return 2;
        }
        return 0;
    }

    private static int parseHour(String clock) {
        if (clock == null || clock.length() < 2) {
            return -1;
        }
        int colon = clock.indexOf(':');
        try {
            return Integer.parseInt(colon > 0 ? clock.substring(0, colon) : clock.substring(0, 2));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String extractAfterSwap(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?:换成|改成|换成附近的|swap (?:it )?for|change (?:it )?to)\\s*(.+)$",
                Pattern.CASE_INSENSITIVE).matcher(message.trim());
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(1).replaceAll("[。.!！?？]+$", "").trim();
        return raw.isBlank() ? null : raw;
    }

    private static boolean isUsable(ItineraryRefineIntent intent, ItineraryPlan plan) {
        if (intent == null || intent.getTargetIndex() == null) {
            return false;
        }
        int size = plan == null || plan.getSchedule() == null ? 0 : plan.getSchedule().size();
        if (intent.resolvedAction() == ActionType.APPEND && size > 0 && intent.getTargetIndex() == size) {
            intent.setTargetIndex(size - 1);
        }
        if (intent.getTargetIndex() < 0 || intent.getTargetIndex() >= size) {
            return false;
        }
        if (intent.resolvedAction() == ActionType.DELETE) {
            return true;
        }
        return intent.getSearchQuery() != null && !intent.getSearchQuery().isBlank();
    }

    private static int lastIndex(List<ItineraryNode> schedule) {
        return Math.max(0, schedule.size() - 1);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (needle != null && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String firstFilled(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return values.length == 0 ? "" : nullToEmpty(values[values.length - 1]);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
