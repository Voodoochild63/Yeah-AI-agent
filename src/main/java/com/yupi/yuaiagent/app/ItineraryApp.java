package com.yupi.yuaiagent.app;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.advisor.ReReadingAdvisor;
import com.yupi.yuaiagent.chatmemory.FileBasedChatMemory;
import com.yupi.yuaiagent.model.dto.ItineraryPlan;
import com.yupi.yuaiagent.model.dto.SunsetEvaluation;
import com.yupi.yuaiagent.service.PoiRagService;
import com.yupi.yuaiagent.service.WeatherService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@Slf4j
public class ItineraryApp {

    private final ChatClient chatClient;

    private final PoiRagService poiRagService;

    private final WeatherService weatherService;

    private static final String SYSTEM_PROMPT = """
        你是一个专业的智能行程规划调度专家。你的职责是协助用户合理安排一天的出行路线。
        
        【核心规则】：
        1. 必须根据用户的意图，从用户的地点备选池中（如 Brunch、咖啡厅、Fine Dining、景点）合理选择并排序。
        2. 必须考虑地理邻近度与逻辑顺序（例如：上午 Brunch -> 下午咖啡/景点 -> 晚上 Fine Dining）。
        3. 在编排路线时，若已绑定外部工具，优先调用天气与交通距离工具获取实际通行时间。
        4. 【动态局部修正规则】：当用户要求更换某个节点（如“下雨去不了某店”、“某店太远了换一家”）时，
           只能精准替换目标节点或给出 2 个室内备选方案供用户选择，禁止无故重算并推翻其他无冲突的既定行程节点。
        5. 输出时保持条理清晰，明确标注到达时间、停留建议及路途耗时。
        """;

    /**
     * 初始化 ChatClient
     *
     * @param dashscopeChatModel
     * @param poiRagService
     * @param weatherService
     */
    public ItineraryApp(ChatModel dashscopeChatModel, PoiRagService poiRagService, WeatherService weatherService) {
        this.poiRagService = poiRagService;
        this.weatherService = weatherService;
//        // 初始化基于文件的对话记忆
//        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
//        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);
        // 初始化基于内存的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
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
     * @return 结构化行程对象
     */
    public ItineraryPlan doPlanItinerary(String message, String chatId) {
        String systemMessage = SYSTEM_PROMPT
                + "\n请务必根据用户需求生成结构化行程方案，包含时间轴节点与恶劣天气/拥堵时的备选建议。";
        String ragResult = poiRagService.searchPoi(message);
        if (ragResult != null && !ragResult.isBlank()) {
            systemMessage += "\n【私藏打卡候选池】：以下是根据用户喜好从私域知识库中匹配出的地点候选，请优先参考并选择这些地点编排行程：\n"
                    + ragResult;
        }
        // 先取当日落日评估，作为编排时间轴终点的决策依据
        SunsetEvaluation sunsetEvaluation = weatherService.evaluateTodaySunset();
        systemMessage += "\n" + buildSunsetRule(sunsetEvaluation);

        ItineraryPlan itineraryPlan = chatClient
                .prompt()
                .system(systemMessage)
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(ItineraryPlan.class);
        if (itineraryPlan != null) {
            // 完整评估元数据挂载到返回的 JSON 根对象
            itineraryPlan.setSunsetEvaluation(sunsetEvaluation);
        }
        log.info("itineraryPlan: {}", itineraryPlan);
        return itineraryPlan;
    }

    /**
     * 依据落日评估结果生成注入大模型的上下文规则
     *
     * @param evaluation 落日评估结果
     * @return 提示词片段
     */
    private String buildSunsetRule(SunsetEvaluation evaluation) {
        if (evaluation == null) {
            return "";
        }
        if (evaluation.recommended()) {
            return ("【日落机位激活】今日天气极佳（日落时间 %s，最佳观赏窗口 %s）。"
                    + "请在下午最后一个行程节点，强制安排落日打卡点（推荐 %s），"
                    + "将其无缝拼接进 schedule 时间轴的终点！并在该节点说明中注明机位贴士：%s")
                    .formatted(evaluation.sunsetTime(), evaluation.goldenHourWindow(),
                            evaluation.recommendedSpot(), evaluation.photoTip());
        }
        return ("【天气不佳提示】今日日落条件欠佳（原因：%s）。"
                + "请不要在 schedule 中强行安排落日户外点，"
                + "而是在 backupSuggestions 列表中贴心提醒用户今日不适宜追日落，并提示：%s")
                .formatted(evaluation.recommendationReason(), evaluation.photoTip());
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
