package com.yeah.yeahaiagent.app;

import com.yeah.yeahaiagent.advisor.MyLoggerAdvisor;
import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.model.dto.RagContext;
import com.yeah.yeahaiagent.service.ItineraryCandidateAttacher;
import com.yeah.yeahaiagent.service.ItineraryDayScope;
import com.yeah.yeahaiagent.service.ItineraryOpeningHoursChecker;
import com.yeah.yeahaiagent.service.ItineraryParkingAttacher;
import com.yeah.yeahaiagent.service.ItineraryPipelineService;
import com.yeah.yeahaiagent.service.ItinerarySafetyGuardrail;
import com.yeah.yeahaiagent.service.ItinerarySessionService;
import com.yeah.yeahaiagent.service.ItineraryTemporalPlanner;
import com.yeah.yeahaiagent.service.ItineraryTimelineNormalizer;
import com.yeah.yeahaiagent.service.ItineraryTransitPlanner;
import com.yeah.yeahaiagent.service.ItineraryPlanSkeletonService;
import com.yeah.yeahaiagent.service.ItineraryRequestFingerprint;
import com.yeah.yeahaiagent.service.ItinerarySunsetBinder;
import com.yeah.yeahaiagent.service.PoiRagService;
import com.yeah.yeahaiagent.service.WeatherService;
import com.yeah.yeahaiagent.resilience.UpstreamApiGuard;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class ItineraryApp {

    private final ChatClient chatClient;

    private final PoiRagService poiRagService;

    private final WeatherService weatherService;

    private final ItineraryParkingAttacher itineraryParkingAttacher;

    private final ItineraryTransitPlanner itineraryTransitPlanner;

    private final ItineraryPipelineService itineraryPipelineService;

    private final ItineraryOpeningHoursChecker itineraryOpeningHoursChecker;

    private final ItineraryCandidateAttacher itineraryCandidateAttacher;

    private final ItinerarySunsetBinder itinerarySunsetBinder;

    private final ItinerarySessionService itinerarySessionService;

    private final ItineraryTemporalPlanner itineraryTemporalPlanner;

    private final ItinerarySafetyGuardrail itinerarySafetyGuardrail;

    private final ItineraryTimelineNormalizer itineraryTimelineNormalizer;

    private final UpstreamApiGuard upstreamApiGuard;

    private final ItineraryPlanSkeletonService itineraryPlanSkeletonService;

    private static final String SYSTEM_PROMPT_ZH = """
        你是一个专业的智能行程规划调度专家。你的职责是协助用户合理安排一天的出行路线。
        
        【核心规则】：
        1. 必须根据用户的意图，从用户的地点备选池中（如 Brunch、咖啡厅、Fine Dining、景点）合理选择并排序。
        2. 必须考虑地理邻近度与逻辑顺序（例如：上午 Brunch -> 下午咖啡/景点 -> 晚上 Fine Dining）。
        3. 在编排路线时，若已绑定外部工具，优先调用天气与交通距离工具获取实际通行时间。
        4. 【动态局部修正规则】：当用户要求更换某个节点（如“下雨去不了某店”、“某店太远了换一家”、“把XX换成YY”）时，
           只能精准替换目标节点或给出 2 个室内备选方案供用户选择，禁止无故重算并推翻其他无冲突的既定行程节点。
           若用户只是提出新探索诉求（想吃 gelato、还想喝杯咖啡、想去散步）且没有说“换成/换掉/不要XX了”，必须追加新节点，严禁覆盖末站或其他未点名的原行程。
        5. 输出时保持条理清晰，明确标注到达时间、停留建议及路途耗时。
        6. 【长度与时段】半日游日常节点默认 4 个，多轮对话可自然扩到 5–6 站，只要当天跨度不超过 22:00；不要因为已经有 3/4 站就拒绝追加，也不要为了凑 4 站去覆盖最后一张卡。若最后一站离开时刻 >= 16:30 或用户要看落日，服务端会再追加就近落日节点，模型先排 4 站即可，不要自己硬塞天文台山。一日游（用户说一日游/全天/full day）必须编排到晚间 20:30-21:30，节点可到 7–8 个。完整动线：上午活动 -> 午餐 -> 下午活动/咖啡 -> 黄金落日打卡 -> 晚餐（约 18:30，必须在落日窗口结束后）-> 可选夜市/海港散步/酒吧清吧/甜品。落日不是一日游的终点。summary、recommendationReason、photoTip、transitToNext、stayDuration 以及任何理由与贴士一律控制在 25 字以内。detailedDescription 不受这 25 字限制。
        7. 【整体规划描述】detailedDescription 必须写一段约 60-120 字的完整规划思路与体验亮点，覆盖路线设计理念、街区氛围、光影或活动特色，以及一天的整体节奏。当 lang 为 zh 时用简体中文；店名、地标、街道名仍保持官方英文。summary 只保留一句话简述，不要把这段长描述塞进 summary。
        8. 【落日时域锚点】黄金落日本身是带严格时间窗口的 Stop。看的是最后一站离开时刻（到达+停留），不是到达时刻。14:52 到达但停留 2 小时、16:52 离开，属于傍晚，应由服务端插入就近落日节点。17:15-18:15（或当日黄金窗）必须锁定为落日黄金窗口：此区间严禁安排 75 分钟正餐；晚饭到达时间必须 >= 落日结束时间（例如 18:15 之后）。观景点必须就近（上一站街区，直线约 5km），严禁从东区跨去 Observatory Hill / CBD。
        9. 【饮酒安全】若某站是酒吧/Pub/Brewery/Nightclub，禁止推荐自驾，交通只写步行、公交或 Uber，不要给停车场。
        
        【停车方案规则 (PARKING LOGIC)】
        1. 绝对严禁凭空捏造具体的商业停车场名字或跨区推荐停车场（如绝不可跨区推荐 Circular Quay 的车库）！不要输出 parkingName、address、rating、虚假步行距离。
        2. 每个需要停车的节点必须生成 parkingSearchKeyword，格式必须严格为：parking near 官方英文店名 所在街区，例如 "parking near Shaw Street Espresso Bexley" 或 "parking near The Grounds of Alexandria"。
        3. parkingTips 仅需根据区域类型给出一条地道简短的停车常识提醒：
           - 如果是居民社区（如 Bexley, Marrickville）：建议寻找 Street Parking（路边 1-2 小时限时或免费车位）。
           - 如果是自带园区/商场（如 The Grounds）：建议停在园区专用访客车位或直属停车场。
           - 如果是 CBD 市中心：提醒车位紧张，建议提前预订商业车库。
        4. 同一街区连续步行节点只在第一站给出停车入口；后续步行点不要再给 parkingSearchKeyword。
        5. 停车入口由服务端按检索词挂到 Google Maps，模型不要编造 Wilson Parking、Secure Parking 等具体库名。

        【地理实体名称强制英文约束 (CRITICAL FOR MAP NAVIGATION)】
        为了确保前端在 Google Maps 中准确定位与连续导航：
        1. `poiName`（打卡点名称）：无论 `lang` 是中文还是英文，必须使用该地点在悉尼当地官方 Google Maps 注册的真实纯英文原名（例如必须输出 "Single O Surry Hills"、"White Rabbit Gallery"、"Edition Coffee Roasters"、"Art Gallery of NSW"，严禁输出类似 "光影艺术设计咖啡馆"、"现代美术馆" 等任何自造的中文翻译名！）。
        2. `address`（地址）：必须保持英文地址标准格式（例如 "1 Alfred St, Sydney NSW 2000"）。
        3. 语言适配范围：当 `lang` 为 zh 时，仅将推荐理由（`description` / `reason` / `recommendationReason`）、活动建议、停留时长、停车避坑贴士（`parkingTips`）等描述性文本翻译为地道中文；所有涉及店名、地标、街道的名字，均保持纯英文！

        【官方网站字段 (websiteUrl)】
        每个 schedule 节点输出可选字段 websiteUrl：
        1. 仅在极高置信度时填入真实官网，例如 The Grounds of Alexandria 填 "https://thegrounds.com.au"。
        2. 无法确定时，填入 Google 官方直达 URL：https://www.google.com/search?btnI=1&q= 加上把「官方英文店名 official website Sydney」做百分号编码后的查询（空格写成 %20）。也可以留空。
        3. 禁止编造不存在的域名。

        【外部社媒检索词规范】
        每个 schedule 节点必须同时输出 searchKeyword 与 redditKeyword：
        1. searchKeyword 给小红书 / RedNote：【官方英文店名】+「拍照 机位 避坑」，例如 "The Grounds of Alexandria 拍照 机位 避坑"。店名保持英文，不要改成中文译名。
        2. redditKeyword 给 Reddit：纯英文店名、城市与品类评价，例如 "The Grounds of Alexandria Sydney coffee review"。禁止任何中文。

        【双语对照字段 (无论 lang 是 zh 还是 en，都必须同时输出)】
        切换界面语言时前端会直接读取这些对照字段，因此一次规划必须同时给出中文和英文，禁止只写当前 lang：
        1. titleZh 与 titleEn。例：titleZh="Alexandria 工业复古半日游"，titleEn="Alexandria Industrial Vintage Half-Day Tour"。title 填与当前 lang 一致的那一份。
        2. summaryZh / summaryEn，detailedDescriptionZh / detailedDescriptionEn。中英意思对等；店名保持官方英文。
        3. 每个 schedule 节点必须输出 categoryZh 与 categoryEn。例：categoryZh="早午餐与绿植园区"，categoryEn="Brunch & Garden"。category 填与当前 lang 一致的那一份。
        4. backupSuggestionsZh 与 backupSuggestionsEn 必须等长、逐条对应。例：
           backupSuggestionsZh=["今日降水概率高达96%，不适宜专程等待落日。","雨后湿地面反光极具质感，适合拍摄街道倒影，请为镜头准备防水布。","若雨势变大，可前往 Mitchell Road Antique Centre 室内挑选复古物件。"]
           backupSuggestionsEn=["Rain probability is about 96% today, so a dedicated sunset wait is not worth it.","Wet streets after rain give strong reflections; bring a rain cover for the lens.","If the rain picks up, browse vintage finds indoors at Mitchell Road Antique Centre."]
           backupSuggestions 填与当前 lang 一致的那一份。

        【锚点辐射探店模式 (Anchor Store Mode)】
        当识别到用户明确想去某一家特定店铺/景点作为核心目的时（例如“专程去 Edition Coffee Roasters”、“想打卡 White Rabbit Gallery，周边随意逛”）：
        1. 主锚点作为核心第一站：将用户指定的店铺作为 schedule 的第一站，标签标为核心打卡点，停留时间建议设为 1.5 - 2 小时。停车检索词必须以该主锚点为基准（parking near 该店名 街区），不要编造车库名。
        2. 卫星漫游点必须步行可达：后续推荐的 2-3 个打卡点，必须严格限定在该主锚点步行 10 分钟以内（直线距离 800 米内）的同街区邻近场所（例如隔壁的独立书店、对面的烘焙坊、转角的街心公园等）。交通方式全部标记为步行，无需让用户重新开车或挪车。
        3. 整体描述突出松弛感：detailedDescription 与 summary 中明确说明“以该店名为核心，探索周边的街巷氛围与小众角落”，节奏强调悠闲慢节奏。
        """;

    private static final String SYSTEM_PROMPT_EN = """
        You are a professional itinerary planner. Your job is to arrange a coherent one-day route.
        
        Core rules:
        1. Pick and order places from the candidate pool according to the user's intent (brunch, cafe, fine dining, sights).
        2. Respect geography and a natural sequence (morning brunch -> afternoon coffee/sights -> evening dining).
        3. If external tools are bound, prefer live weather and transit times.
        4. Local correction: when the user asks to swap one stop, replace only that node or offer two indoor alternatives. Do not rebuild unrelated nodes.
           If they only add a new desire (I want gelato / another coffee / a walk) without saying swap/replace/change, you MUST append a new stop. Never overwrite the last card just to keep the list short.
        5. Keep the timeline clear: arrival time, stay duration, and transit to the next stop.
        6. Length and hours: half-day defaults to 4 daily stops and may grow to 5–6 in follow-up turns if the day still ends before 22:00 — never refuse an extra stop just because the list already has 3 or 4 cards, and never overwrite the last card to stay at 4. If the last stop's departure (arrival + stay) is 16:30 or later, or the user asked for sunset, the server will append a nearby sunset node — do not invent Observatory Hill yourself. A full-day request (一日游 / full day / all day) MUST run until 20:30-21:30, up to 7–8 stops: morning activity -> lunch -> afternoon activity/coffee -> golden-hour sunset -> dinner around 18:30 AFTER the sunset window -> optional night market / harbour walk / bar / dessert. Sunset is not the end of a full day. Keep summary, reasons, photo tips, transit notes, and stay notes within 25 words each. detailedDescription is exempt from that cap.
        7. Overall narrative: detailedDescription must be one paragraph of about 60-120 words covering the route idea, neighbourhood atmosphere, light or activity character, and the day's pacing. When lang is en, write it in English. Keep shop, landmark, and street names in their official English. summary stays a single sentence; do not paste this paragraph into summary.
        8. Sunset time-anchor: golden hour is itself a Stop with a hard window. Use last-stop departure (arrival + stay), not arrival. 14:52 arrival with a 2-hour stay leaves at 16:52 and should get a nearby sunset node from the server. Lock 17:15-18:15 (or today's golden-hour window): never put a 75-minute sit-down dinner inside it. Dinner arrival MUST be at or after the sunset window end (e.g. 18:15+). Viewpoints must stay in the previous suburb (about 5km). Never jump from the eastern suburbs to Observatory Hill or the CBD.
        9. Alcohol safety: if a stop is a bar / pub / brewery / nightclub, never recommend driving. Transit must be walking, public transport, or Uber. Do not attach parking.
        
        [Parking — PARKING LOGIC]
        1. Never invent a commercial car-park name or recommend a garage in another suburb (never send someone to Circular Quay parking for a Chippendale stop). Do not output parkingName, address, rating, or a fake walking distance.
        2. Every stop that needs a car MUST include parkingSearchKeyword in the exact form: parking near official English venue name suburb. Examples: "parking near Shaw Street Espresso Bexley", "parking near The Grounds of Alexandria".
        3. parkingTips is one short local reminder only:
           - Residential streets (Bexley, Marrickville): look for street parking, often 1-2 hour or free kerbside bays.
           - Venue/garden/mall (The Grounds): use the on-site visitor bays or the venue car park.
           - CBD: bays are tight; book a commercial garage ahead.
        4. In a walkable cluster, only the first stop gets a parking search entry.
        5. The server turns the keyword into a Google Maps link. Do not invent Wilson Parking or Secure Parking names.

        [Geographic names MUST stay official English — CRITICAL FOR MAP NAVIGATION]
        This rule applies whether lang is zh or en, so Google Maps search and turn-by-turn directions resolve the real place:
        1. poiName: the venue's real English name as registered on Google Maps in Sydney. Examples: "Single O Surry Hills", "White Rabbit Gallery", "Edition Coffee Roasters", "Art Gallery of NSW". Never invent a translated name such as "光影艺术设计咖啡馆" or "现代美术馆".
        2. parkingSearchKeyword stays English, in the form parking near venue suburb. Do not translate.
        3. address: standard English format. Example: "1 Alfred St, Sydney NSW 2000".
        4. Language scope: when lang is zh, translate only descriptive copy (description / reason / recommendationReason, activity notes, stay duration, parkingTips) into natural Chinese. Every shop, landmark, and street name stays pure English.

        [websiteUrl]
        Every schedule node may include websiteUrl:
        1. Fill a real official site only when you are highly confident, for example "https://thegrounds.com.au" for The Grounds of Alexandria.
        2. If unsure, output a Google I'm Feeling Lucky URL: https://www.google.com/search?btnI=1&q= plus the percent-encoded query of the official English venue name plus official website Sydney (spaces as %20). You may also leave it empty.
        3. Never invent a domain.

        [Social search keywords]
        Every schedule node MUST include both searchKeyword and redditKeyword:
        1. searchKeyword is for RedNote: official English venue name plus the Chinese intent words 拍照 机位 避坑. Example: "The Grounds of Alexandria 拍照 机位 避坑". Keep the venue name in English.
        2. redditKeyword is for Reddit and must be pure English: venue, Sydney, and a review phrase. Example: "The Grounds of Alexandria Sydney coffee review". No Chinese characters.

        [Bilingual fields — ALWAYS output both Chinese and English]
        The UI switches language without calling the model again, so every plan MUST include both versions:
        1. titleZh and titleEn. Example: titleZh="Alexandria 工业复古半日游", titleEn="Alexandria Industrial Vintage Half-Day Tour". title matches the current lang.
        2. summaryZh / summaryEn and detailedDescriptionZh / detailedDescriptionEn, equivalent meaning. Keep official English place names.
        3. Every schedule node MUST include categoryZh and categoryEn. Example: categoryZh="早午餐与绿植园区", categoryEn="Brunch & Garden". category matches the current lang.
        4. backupSuggestionsZh and backupSuggestionsEn MUST be the same length and aligned item by item. backupSuggestions matches the current lang.

        【语言纯度强制规范 (LANGUAGE INTEGRITY)】
        当请求参数 lang 为 en 时：
        1. 除双语对照的 *Zh 字段、searchKeyword，以及必须保持英文的店名、地名、路名之外，其余描述性字段必须输出地道英文。
        2. titleEn、summaryEn、detailedDescriptionEn、categoryEn、backupSuggestionsEn、stayDuration、transitToNext 必须是英文。
        3. titleZh、summaryZh、detailedDescriptionZh、categoryZh、backupSuggestionsZh 必须是地道中文（店名仍保持官方英文）。

        [Anchor Store Mode]
        When the user clearly wants one specific shop or sight as the core purpose (for example "go specifically to Edition Coffee Roasters" or "check in at White Rabbit Gallery and wander nearby"):
        1. Put that venue first as schedule stop 1. Tag it as the core stop and suggest a 1.5-2 hour stay. Parking search must be based on this anchor (parking near that venue), never an invented garage.
        2. The next 2-3 stops MUST be walkable satellites within 10 minutes / 800 metres of the anchor, same neighbourhood (bookshop next door, bakery across the street, a pocket park around the corner). Mark every transit as walking. Do not ask the traveller to drive or repark.
        3. summary and detailedDescription must say the day is anchored at that venue and then explores nearby lanes and lesser-known corners, at an unhurried pace.
        """;

    /**
     * 初始化 ChatClient
     *
     * @param dashscopeChatModel
     * @param poiRagService
     * @param weatherService
     */
    public ItineraryApp(ChatModel dashscopeChatModel, PoiRagService poiRagService,
                        WeatherService weatherService, ItineraryParkingAttacher itineraryParkingAttacher,
                        ItineraryTransitPlanner itineraryTransitPlanner,
                        ItineraryPipelineService itineraryPipelineService,
                        ItineraryOpeningHoursChecker itineraryOpeningHoursChecker,
                        ItineraryCandidateAttacher itineraryCandidateAttacher,
                        ItinerarySunsetBinder itinerarySunsetBinder,
                        ItinerarySessionService itinerarySessionService,
                        ItineraryTemporalPlanner itineraryTemporalPlanner,
                        ItinerarySafetyGuardrail itinerarySafetyGuardrail,
                        ItineraryTimelineNormalizer itineraryTimelineNormalizer,
                        UpstreamApiGuard upstreamApiGuard,
                        ItineraryPlanSkeletonService itineraryPlanSkeletonService) {
        this.poiRagService = poiRagService;
        this.weatherService = weatherService;
        this.itineraryParkingAttacher = itineraryParkingAttacher;
        this.itineraryTransitPlanner = itineraryTransitPlanner;
        this.itineraryPipelineService = itineraryPipelineService;
        this.itineraryOpeningHoursChecker = itineraryOpeningHoursChecker;
        this.itineraryCandidateAttacher = itineraryCandidateAttacher;
        this.itinerarySunsetBinder = itinerarySunsetBinder;
        this.itinerarySessionService = itinerarySessionService;
        this.itineraryTemporalPlanner = itineraryTemporalPlanner;
        this.itinerarySafetyGuardrail = itinerarySafetyGuardrail;
        this.itineraryTimelineNormalizer = itineraryTimelineNormalizer;
        this.upstreamApiGuard = upstreamApiGuard;
        this.itineraryPlanSkeletonService = itineraryPlanSkeletonService;
//        // 初始化基于文件的对话记忆
//        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
//        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);
        // 初始化基于内存的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        chatClient = ChatClient.builder(dashscopeChatModel)
                // 提示词是字面量，不走 StringTemplate。否则 JSON schema 或文案里的 {} 会被当成占位符。
                .defaultTemplateRenderer((template, variables) -> template)
                .defaultSystem(SYSTEM_PROMPT_ZH)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // 自定义日志 Advisor，可按需开启
                        new MyLoggerAdvisor()
//                        // 自定义推理增强 Advisor，可按需开启
//                       ,new ReReadingAdvisor()
                )
                .build();
    }

    /**
     * AI 基础对话（支持多轮对话记忆）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 基础对话（支持多轮对话记忆，SSE 流式传输）
     *
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }

    /**
     * AI 结构化行程规划（支持多轮动态修正与备选建议）
     *
     * @param message 用户的偏好或修改指令（如“排一条路线”或“下雨了把第二家换成室内咖啡馆”）
     * @param chatId  会话 ID，保证多轮修正时的上下文连贯
     * @param lang    输出语言，en / zh，默认 zh
     * @return 结构化行程对象
     */
    public ItineraryPlan doPlanItinerary(String message, String chatId, String lang) {
        return doPlanItinerary(message, chatId, lang, null);
    }

    public ItineraryPlan doPlanItinerary(String message, String chatId, String lang, RagContext ragContext) {
        String key = ItineraryRequestFingerprint.key(message, lang, ragContext);
        ItineraryPlan itineraryPlan = itineraryPlanSkeletonService.getOrCompute(key,
                () -> generatePlan(message, chatId, lang, ragContext));
        if (itineraryPlan != null) {
            itinerarySessionService.begin(itineraryPlan);
        }
        log.info("itineraryPlan: {}", itineraryPlan);
        return itineraryPlan;
    }

    private ItineraryPlan generatePlan(String message, String chatId, String lang, RagContext ragContext) {
        LanguageEnum language = LanguageEnum.fromCode(lang);
        try {
            ItineraryPipelineService.PipelineContext pipeline = retrieveVerifiedPool(message, ragContext);
            SunsetEvaluation sunsetEvaluation = weatherService.evaluateTodaySunset(language.getCode());
            String systemMessage = assembleSystemPrompt(message, ragContext, pipeline, sunsetEvaluation, language);
            ItineraryPlan itineraryPlan = requestPlan(message, chatId, systemMessage, language);
            applyPostGeneration(itineraryPlan, sunsetEvaluation, language, pipeline, message);
            return itineraryPlan;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("itinerary plan failed. message={}, chatId={}, lang={}", message, chatId, lang, ex);
            if (isRateLimited(ex)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "当前访问过于频繁，请稍后再试。已触发本地限流保护，避免上游接口过载。", ex);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, summarizePlanFailure(ex), ex);
        }
    }

    private ItineraryPipelineService.PipelineContext retrieveVerifiedPool(String message, RagContext ragContext) {
        try {
            return itineraryPipelineService.retrieve(message, ragContext);
        } catch (RuntimeException retrieveEx) {
            log.warn("agentic retrieve failed, continue with empty verified pool", retrieveEx);
            return new ItineraryPipelineService.PipelineContext(List.of(), List.of());
        }
    }

    private String assembleSystemPrompt(String message,
                                        RagContext ragContext,
                                        ItineraryPipelineService.PipelineContext pipeline,
                                        SunsetEvaluation sunsetEvaluation,
                                        LanguageEnum language) {
        StringBuilder systemMessage = new StringBuilder();
        systemMessage.append(buildCriticalLanguageRule(language))
                .append("\n\n")
                .append(language.text(SYSTEM_PROMPT_ZH, SYSTEM_PROMPT_EN))
                .append('\n')
                .append(language.text(
                        "请务必根据用户需求生成结构化行程方案，包含时间轴节点与恶劣天气/拥堵时的备选建议。",
                        "Always return a structured itinerary with a timeline and backup suggestions for rain or congestion."))
                .append('\n')
                .append(buildAgenticConstraint(pipeline, ragContext, language));
        String ragResult = poiRagService.searchPoi(message);
        if (ragResult != null && !ragResult.isBlank()) {
            systemMessage.append('\n')
                    .append(language.text(
                            "【私藏打卡候选池】：以下是根据用户喜好从私域知识库中匹配出的地点候选，请优先参考并选择这些地点编排行程。poiName 必须原样使用候选里的官方英文店名，禁止翻译：\n",
                            "[Private POI pool] Use these matched venues as the first choice when building the route. Context may be in another language; keep official place names and write all output fields in the required language:\n"))
                    .append(ragResult);
        }
        systemMessage.append('\n').append(buildSunsetRule(sunsetEvaluation, message, language));
        if (isDrivingScenario(message)) {
            systemMessage.append('\n').append(language.text(
                    "【自驾场景】用户可能开车出行。请在 commute/transitToNext 中优先考虑停车与步行衔接；不要编造具体停车场名称。每个需要停车的节点只给 parkingSearchKeyword（parking near 店名 街区），由 Google Maps 打开真实车位。同一街区连续步行节点只保留第一处停车入口。",
                    "[Driving] The user may be driving. Prefer parking-to-walking connections. Do not invent garage names. Each stop that needs a car only gets parkingSearchKeyword (parking near venue suburb) so Google Maps can show real bays. Keep parking only on the first stop of a walkable cluster."));
        }
        String anchorRule = buildAnchorStoreRule(message, language);
        if (!anchorRule.isBlank()) {
            systemMessage.append('\n').append(anchorRule);
        }
        systemMessage.append('\n').append(buildCriticalLanguageRule(language));
        return systemMessage.toString();
    }

    private void applyPostGeneration(ItineraryPlan itineraryPlan,
                                     SunsetEvaluation sunsetEvaluation,
                                     LanguageEnum language,
                                     ItineraryPipelineService.PipelineContext pipeline,
                                     String message) {
        if (itineraryPlan == null) {
            return;
        }
        if (pipeline != null) {
            itineraryTransitPlanner.rememberPlaces(pipeline.verifiedPlaces());
        }
        itineraryTransitPlanner.apply(itineraryPlan, language);
        itineraryParkingAttacher.attach(itineraryPlan, language);
        itineraryOpeningHoursChecker.apply(
                itineraryPlan,
                pipeline == null ? List.of() : pipeline.verifiedPlaces(),
                language);
        itineraryCandidateAttacher.attach(
                itineraryPlan,
                pipeline == null ? List.of() : pipeline.verifiedPlaces(),
                language);
        itinerarySunsetBinder.apply(itineraryPlan, sunsetEvaluation, message, language);
        itineraryTransitPlanner.apply(itineraryPlan, language);
        itineraryTemporalPlanner.apply(itineraryPlan, language);
        itineraryTimelineNormalizer.apply(itineraryPlan, language);
        itineraryOpeningHoursChecker.applyStatus(
                itineraryPlan,
                pipeline == null ? List.of() : pipeline.verifiedPlaces(),
                language);
        itineraryParkingAttacher.attach(itineraryPlan, language);
        itinerarySafetyGuardrail.apply(itineraryPlan, language);
        applyBilingualCopy(itineraryPlan, language);
    }

    /**
     * 模型漏填对照字段时，用当前语言的主字段补上，方便前端切换语言。
     */
    private void applyBilingualCopy(ItineraryPlan plan, LanguageEnum language) {
        boolean english = language != null && !language.isZh();
        plan.setTitleZh(firstFilled(plan.getTitleZh(), english ? null : plan.getTitle()));
        plan.setTitleEn(firstFilled(plan.getTitleEn(), english ? plan.getTitle() : null));
        plan.setSummaryZh(firstFilled(plan.getSummaryZh(), english ? null : plan.getSummary()));
        plan.setSummaryEn(firstFilled(plan.getSummaryEn(), english ? plan.getSummary() : null));
        plan.setDetailedDescriptionZh(firstFilled(plan.getDetailedDescriptionZh(),
                english ? null : plan.getDetailedDescription()));
        plan.setDetailedDescriptionEn(firstFilled(plan.getDetailedDescriptionEn(),
                english ? plan.getDetailedDescription() : null));
        if (plan.getBackupSuggestionsZh() == null || plan.getBackupSuggestionsZh().isEmpty()) {
            plan.setBackupSuggestionsZh(english ? plan.getBackupSuggestionsZh() : copyList(plan.getBackupSuggestions()));
        }
        if (plan.getBackupSuggestionsEn() == null || plan.getBackupSuggestionsEn().isEmpty()) {
            plan.setBackupSuggestionsEn(english ? copyList(plan.getBackupSuggestions()) : plan.getBackupSuggestionsEn());
        }
        if (plan.getSchedule() == null) {
            return;
        }
        for (ItineraryNode node : plan.getSchedule()) {
            if (node == null) {
                continue;
            }
            node.setCategoryZh(firstFilled(node.getCategoryZh(), english ? null : node.getCategory()));
            node.setCategoryEn(firstFilled(node.getCategoryEn(), english ? node.getCategory() : null));
        }
    }

    private static String firstFilled(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null || fallback.isBlank() ? preferred : fallback.trim();
    }

    private static List<String> copyList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        return List.copyOf(source);
    }

    private boolean isDrivingScenario(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("自驾")
                || normalized.contains("开车")
                || normalized.contains("停车")
                || normalized.contains("drive")
                || normalized.contains("parking")
                || normalized.contains("driving");
    }

    /**
     * 用户点名一家店/馆，并想以它为圆心在周边漫游时，切换为锚点辐射规划。
     */
    private String buildAnchorStoreRule(String message, LanguageEnum language) {
        String venue = extractAnchorStore(message);
        if (venue == null) {
            return "";
        }
        return language.text(
                """
                【锚点辐射探店模式已激活】
                用户指定的主锚点是：__VENUE__。请立刻切换为锚点辐射规划，不要排跨区赶路行程。
                1. schedule 第一站必须是 __VENUE__，标签用「核心打卡点」/ categoryEn 用 "Core stop"，停留 1.5-2 小时。停车检索词只挂在这一站：parking near __VENUE__ 所在街区，不要编造车库名。
                2. 后续只再排 2-3 个卫星点，全部必须在 __VENUE__ 步行 10 分钟以内、直线约 800 米内的同街区（隔壁书店、对面烘焙、转角公园等）。transitToNext 一律步行，禁止开车或挪车。
                3. summary 与 detailedDescription 写明：以 __VENUE__ 为核心，探索周边街巷氛围与小众角落，节奏悠闲。
                """.replace("__VENUE__", venue),
                """
                [Anchor Store Mode is ON]
                The user's core venue is: __VENUE__. Switch to an anchored neighbourhood wander. Do not build a cross-suburb driving loop.
                1. schedule first stop MUST be __VENUE__, tagged as the core stop, stay 1.5-2 hours. Parking search only on this stop: parking near __VENUE__ and its suburb. Do not invent a garage name.
                2. Add only 2-3 satellite stops, all within a 10-minute walk / about 800 metres of __VENUE__ in the same neighbourhood. Every transitToNext is walking. No driving or reparking.
                3. summary and detailedDescription must say the day is centred on __VENUE__, exploring nearby lanes and lesser-known corners at an unhurried pace.
                """.replace("__VENUE__", venue));
    }

    private String extractAnchorStore(String message) {
        if (message == null || message.isBlank() || looksLikeLinearRoute(message)) {
            return null;
        }
        String fromPool = poiRagService.findNamedVenue(message);
        if (fromPool != null) {
            return fromPool;
        }
        if (!hasAnchorIntent(message)) {
            return null;
        }
        Matcher quoted = Pattern.compile("[\"“「]([^\"”」]{2,80})[\"”」]").matcher(message);
        if (quoted.find()) {
            return quoted.group(1).trim();
        }
        Matcher named = Pattern.compile("\\b([A-Z][A-Za-z0-9'&.-]+(?:\\s+[A-Z][A-Za-z0-9'&.-]+){1,5})\\b")
                .matcher(message);
        while (named.find()) {
            String candidate = named.group(1).trim();
            if (!isAreaOrCityName(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean hasAnchorIntent(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("专程")
                || normalized.contains("打卡")
                || normalized.contains("专门去")
                || normalized.contains("想去")
                || normalized.contains("周边")
                || normalized.contains("附近逛")
                || normalized.contains("随便逛")
                || normalized.contains("specifically")
                || normalized.contains("check in")
                || normalized.contains("check-in")
                || normalized.contains("dedicated")
                || normalized.contains("wander")
                || normalized.contains("nearby")
                || normalized.contains("around it");
    }

    private static boolean looksLikeLinearRoute(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        boolean coastalDay = normalized.contains("半日")
                || normalized.contains("一日")
                || normalized.contains("海边")
                || normalized.contains("half-day")
                || normalized.contains("half day")
                || normalized.contains("coastal");
        return coastalDay && (normalized.contains("到") || normalized.contains(" to "));
    }

    private static boolean isAreaOrCityName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).trim();
        return normalized.equals("sydney")
                || normalized.equals("new south wales")
                || normalized.endsWith(" beach")
                || normalized.endsWith(" hills")
                || normalized.equals("bondi")
                || normalized.equals("bronte")
                || normalized.equals("chippendale")
                || normalized.equals("haymarket")
                || normalized.equals("newtown")
                || normalized.equals("surry hills")
                || normalized.equals("the rocks")
                || normalized.equals("circular quay");
    }

    /**
     * 依据落日评估结果生成注入大模型的上下文规则
     *
     * @param evaluation 落日评估结果
     * @return 提示词片段
     */
    private String buildSunsetRule(SunsetEvaluation evaluation, String message, LanguageEnum language) {
        boolean userAsked = ItinerarySunsetBinder.wantsSunset(message);
        boolean fullDay = ItineraryDayScope.isFullDay(message);
        StringBuilder rule = new StringBuilder();
        rule.append(language.text(
                "【落日时域锚点】判断的是最后一站离开时刻=到达+停留，不是到达时刻。14:52 到达、停留 2 小时则 16:52 离开，必须给落日。半日游模型先排约 4 个日常节点，多轮可再追加到 5–6 站（不超过 22:00）；服务端会在离开时刻 >= 16:30 或用户点名落日时插入就近观景点。17:15-18:15（或当日黄金窗）锁定为落日窗口：严禁在此安排 75 分钟正餐，晚饭必须在窗口结束之后到达。落日必须就近（上一站街区，直线约 5km），严禁从 Randwick / Coogee 跨去 Observatory Hill。禁止泛词 Sydney sunset。",
                "[Sunset time-anchor] Use last-stop departure = arrival + stay, not arrival. A 14:52 arrival with a 2-hour stay leaves at 16:52 and MUST get sunset. Half-day: start with about 4 daily stops and may grow to 5–6 before 22:00 — never refuse or overwrite a stop just because the list already has 3 or 4 cards. The server inserts a nearby lookout when departure is 16:30+ or the user asked. Lock 17:15-18:15 (or today's golden hour): no 75-minute dinner inside it; dinner arrives after the window ends. Stay within ~5km of the previous suburb. Never jump from Randwick/Coogee to Observatory Hill. Never use a generic Sydney sunset query."));
        if (fullDay) {
            rule.append('\n').append(language.text(
                    "【一日游晚间编排】行程终止时间 20:30-21:30。动线必须是：上午活动 -> 午餐 -> 下午活动/咖啡 -> 黄金落日打卡 -> 晚餐（约 18:30，落日结束后）-> 可选夜市/海港散步/酒吧。落日不是终点，不要在日落节点写「行程结束」。",
                    "[Full-day evening] End between 20:30 and 21:30. Topology: morning -> lunch -> afternoon/coffee -> golden-hour sunset -> dinner around 18:30 after sunset -> optional night market / harbour walk / bar. Sunset is not the finale. Do not write End of itinerary on the sunset stop."));
        }
        if (userAsked) {
            rule.append('\n').append(language.text(
                    "用户明确想看落日：由服务端插入就近观景点，不要自己写天文台山。",
                    "The user asked for sunset: the server will insert a nearby lookout. Do not name Observatory Hill."));
        }
        if (evaluation == null) {
            return rule.toString();
        }
        if (evaluation.recommended()) {
            rule.append('\n').append(language.text(
                    ("今日天气适合晚霞（日落 %s，黄金窗 %s）。黄金窗口必须留给落日打卡，晚饭排在窗口结束之后。")
                            .formatted(evaluation.sunsetTime(), evaluation.goldenHourWindow()),
                    ("Weather is good for colour (sunset %s, golden hour %s). Keep the golden-hour window for the lookout; put dinner after it ends.")
                            .formatted(evaluation.sunsetTime(), evaluation.goldenHourWindow())));
            return rule.toString();
        }
        rule.append('\n').append(language.text(
                ("今日日落条件欠佳（%s）。不要在 schedule 里强行安排落日户外点；若用户没提落日，也不要在 backupSuggestions 里推销追日。")
                        .formatted(evaluation.recommendationReason()),
                ("Sunset conditions are poor (%s). Do not force an outdoor sunset node. If the user did not ask for sunset, do not push sunset-chasing in backupSuggestions.")
                        .formatted(evaluation.recommendationReason())));
        return rule.toString();
    }

    private String buildAgenticConstraint(ItineraryPipelineService.PipelineContext pipeline,
                                          RagContext ragContext,
                                          LanguageEnum language) {
        String verified = itineraryPipelineService.formatVerifiedPool(
                pipeline == null ? List.of() : pipeline.verifiedPlaces());
        String taste = itineraryPipelineService.formatTasteAndPrivateRag(ragContext);
        return """
                【真实物理候选池】
                以下是 Google Maps 上真实存在且评分 4.0 以上、评价不少于 20 条的高质候选地点：
                __VERIFIED__

                【用户个人审美与私域知识库 (RAG)】
                __TASTE__

                【任务指令】
                请严格作为一名高级品味主理人：
                1. 从上述真实物理候选池或用户的私域偏好库中，挑选 1 个核心打卡锚点与 1-2 个周边卫星点。绝对禁止凭空捏造地点！
                2. 基于用户的 RAG 审美画像，为挑选出的地点贴上专属的氛围标签，并以极其地道的语言生成半日游路线。
                3. 语言纯度：如果当前 lang 为 en，所有返回字段（除原生地名、parkingSearchKeyword 外）必须为纯英文。
                4. 候选池若带 lat/lng，把同一组数字原样写入对应节点的 latitude、longitude，不要估算。
                """
                .replace("__VERIFIED__", verified)
                .replace("__TASTE__", taste)
                + (language.isZh() ? "" : "\nThis request is lang=en. Descriptive fields must be English.");
    }

    private ItineraryPlan requestPlan(String message, String chatId, String systemMessage, LanguageEnum language) {
        if (upstreamApiGuard != null) {
            upstreamApiGuard.acquireLlm();
        }
        String lang = language.getCode();
        String userMessage = (message == null ? "" : message)
                + "\n\n[lang=" + lang + "] "
                + language.text(
                "本次展示语言是简体中文。title、summary、category、backupSuggestions 用中文；同时必须填写 titleEn、categoryEn、backupSuggestionsEn 等英文对照。店名、路名、停车场名保持官方英文。",
                "The display language is English. title, summary, category, backupSuggestions must be English. You MUST still fill titleZh, categoryZh, backupSuggestionsZh and the other Chinese counterparts. Keep official English place names.");
        return chatClient
                .prompt()
                .templateRenderer((template, variables) -> template)
                .system(systemMessage)
                .user(userMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId + ":" + lang))
                .call()
                .entity(ItineraryPlan.class);
    }

    private static boolean isRateLimited(Throwable ex) {
        RestClientResponseException rest = findRestError(ex);
        if (rest != null && rest.getStatusCode().value() == 429) {
            return true;
        }
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("HTTP 429") || message.contains("RESOURCE_EXHAUSTED")
                    || message.contains("Quota exceeded"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RestClientResponseException findRestError(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof RestClientResponseException rest) {
                return rest;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 把模型调用或 JSON 反序列化失败收成一行，避免未捕获异常变成没有原因的 500。
     */
    private static String summarizePlanFailure(Throwable ex) {
        if (isPromptAssemblyError(ex)) {
            return "行程规划暂时无法完成，请稍后重试。";
        }
        StringBuilder summary = new StringBuilder();
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < 6) {
            if (depth > 0) {
                summary.append(" | ");
            }
            summary.append(current.getClass().getSimpleName());
            if (current instanceof RestClientResponseException rest) {
                summary.append(' ').append(rest.getStatusCode().value()).append(' ');
                String body = rest.getResponseBodyAsString();
                if (body != null && !body.isBlank()) {
                    summary.append(body.replaceAll("\\s+", " ").trim());
                }
            } else if (current.getMessage() != null && !current.getMessage().isBlank()) {
                summary.append(": ").append(current.getMessage().replaceAll("\\s+", " ").trim());
            }
            current = current.getCause();
            depth++;
        }
        String text = summary.toString();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static boolean isPromptAssemblyError(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof IllegalFormatException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Conversion =")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 放在提示词首尾。lang 用本次请求的真实值，避免被默认成中文。
     * 不用 String.formatted：提示词里的 96% 会被当成格式符，抛出 Conversion = '，'。
     */
    private String buildCriticalLanguageRule(LanguageEnum language) {
        String lang = language.getCode();
        return """
                【语言输出最高指令 (CRITICAL MULTI-LANGUAGE RULE)】
                当前请求的目标语言参数为：`lang = __LANG__`。
                非专有名称文本必须遵照 `__LANG__` 生成当前展示字段；同时必须输出完整中英对照，让前端切换语言时无需重新规划。

                【双语对照，无论 lang 是什么都必须填写】
                - titleZh / titleEn。例：titleZh="Alexandria 工业复古半日游"，titleEn="Alexandria Industrial Vintage Half-Day Tour"
                - summaryZh / summaryEn，detailedDescriptionZh / detailedDescriptionEn
                - schedule[].categoryZh / schedule[].categoryEn。例：categoryZh="早午餐与绿植园区"，categoryEn="Brunch & Garden"
                - backupSuggestionsZh / backupSuggestionsEn：等长、逐条对应。例：
                  Zh「今日降水概率高达96%，不适宜专程等待落日。」
                  En "Rain probability is about 96% today, so a dedicated sunset wait is not worth it."
                title、summary、detailedDescription、category、backupSuggestions 填写与当前 lang 一致的那一份。

                如果 lang 为 en：
                1. *En 字段以及 stayDuration、transitToNext 必须是地道英文，禁止把这些字段写成中文。
                2. *Zh 字段必须是地道中文（店名、路名保持官方英文）。
                3. 店名、地名、路名、停车场名必须保持当地官方英文名。searchKeyword 可保留「拍照 机位 避坑」。

                如果 lang 为 zh：
                - 店名、路名、停车场名保持官方纯英文；
                - *Zh 与 title、summary、category、backupSuggestions 用中文；*En 仍必须给出对应英文。
                """.replace("__LANG__", lang);
    }

    // AI 恋爱知识库问答功能
    /*
    @Resource
    private VectorStore loveAppVectorStore;

    @Resource
    private Advisor loveAppRagCloudAdvisor;

    @Resource
    private VectorStore pgVectorVectorStore;

    @Resource
    private QueryRewriter queryRewriter;
*/
        /*
    /**
     * 和 RAG 知识库进行对话
     *
     * @param message
     * @param chatId
     * @return
     */
    /*
    public String doChatWithRag(String message, String chatId) {
        // 查询重写
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse chatResponse = chatClient
                .prompt()
                // 使用改写后的查询
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                // 应用 RAG 知识库问答
                .advisors(new QuestionAnswerAdvisor(loveAppVectorStore))
                // 应用 RAG 检索增强服务（基于云知识库服务）
//                .advisors(loveAppRagCloudAdvisor)
                // 应用 RAG 检索增强服务（基于 PgVector 向量存储）
//                .advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))
                // 应用自定义的 RAG 检索增强服务（文档查询器 + 上下文增强器）
//                .advisors(
//                        LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(
//                                loveAppVectorStore, "单身"
//                        )
//                )
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
    */

    // AI 调用工具能力
    @Resource
    private ToolCallback[] allTools;

    /**
     * AI 恋爱报告功能（支持调用工具）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithTools(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    // AI 调用 MCP 服务

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    /**
     * AI 恋爱报告功能（调用 MCP 服务）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithMcp(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
}
