# Yeah AI Agent 项目全解（面试自学版）

> 这份文档假设你**完全没接触过 Spring AI**，从最基础的概念讲起，一直讲到每个类、每行关键配置在做什么，以及"为什么这么设计"。
>
> 阅读建议：第 1～3 章建立整体印象，第 4 章熟悉目录，第 5 章是**面试最常被追问的主链路**，第 6 章是各模块深挖，第 10 章是自己项目的短板（面试官问"有什么不足"时用）。

---

## 目录

1. [这个项目是什么](#1-这个项目是什么)
2. [先补齐 8 个基础概念](#2-先补齐-8-个基础概念)
3. [技术栈全清单](#3-技术栈全清单)
4. [项目结构逐目录讲解](#4-项目结构逐目录讲解)
5. [核心主链路：一次请求的完整旅程](#5-核心主链路一次请求的完整旅程)
6. [六大核心模块深度讲解](#6-六大核心模块深度讲解)
7. [配置文件逐段解释](#7-配置文件逐段解释)
8. [测试体系](#8-测试体系)
9. [如何跑起来](#9-如何跑起来)
10. [已知短板与技术债](#10-已知短板与技术债)
11. [面试问答速查](#11-面试问答速查)
12. [附录：术语表](#12-附录术语表)

---

## 1. 这个项目是什么

### 1.1 一句话版本

一个基于 Spring AI + Gemini 的**悉尼本地生活行程规划智能体**：用户用一句自然语言描述偏好，系统结合私域打卡点库、实时天气落日算法和停车数据，产出一份带时间轴的**强类型结构化行程 JSON**，并支持一键导出中英文双语排版 PDF。

### 1.2 大白话版本

想象你是一个刚到悉尼的人，你对着一个输入框说：

> "帮我安排周六在 Surry Hills 的半日游，我喜欢精品咖啡和黑胶唱片，我开车去。"

普通的 ChatGPT 会给你一段散文，里面的店可能是**编造的**，时间也是拍脑袋的。这个项目做的事情是：

| 环节 | 普通 ChatGPT | 本项目 |
|---|---|---|
| 店铺来源 | 模型脑补，可能不存在 | 先从本地私域库检索真实店铺，塞进提示词 |
| 输出格式 | 一段文字 | 严格的 JSON 对象，前端可直接渲染 |
| 天气 | 不知道 | 实时调 Open-Meteo，云量/降水算出"今天值不值得追日落" |
| 停车 | 模型编一个停车场名字 | 后端从真实停车场库匹配后**覆盖**模型输出 |
| 语言 | 中英混杂 | 提示词里加"语言锁"，强制全中文或全英文 |
| 导出 | 手动复制 | 一键生成杂志质感 PDF |

### 1.3 一个真实的请求与响应

**请求**

```
GET /api/ai/itinerary/plan?message=帮我安排 Surry Hills 咖啡和黑胶半日游，我开车&chatId=user_1001&lang=zh
```

**响应（结构简化）**

```json
{
  "title": "悉尼 Surry Hills 黑胶与咖啡半日游",
  "summary": "从 Reservoir St 的手冲开始，穿过 Crown St 淘一张 Soul 黑胶……",
  "schedule": [
    {
      "nodeId": "node-1",
      "poiName": "Single O Surry Hills",
      "category": "Brunch/精品咖啡",
      "arrivalTime": "10:00",
      "stayDuration": "60 分钟",
      "transitToNext": "步行 8 分钟前往 The Record Store",
      "parkingInfo": {
        "hasParkingRecommendation": true,
        "parkingName": "Wilson Parking - Citipark Surry Hills",
        "address": "2-38 Baptist St, Surry Hills NSW 2010",
        "rating": 4.0,
        "walkingEstimate": "步行约 4 分钟 (320m)",
        "tip": "距离 Crown St / Reservoir St 咖啡带步行很近，周末建议 10 点前到"
      }
    }
  ],
  "backupSuggestions": ["若遇雨，可改去 White Rabbit Gallery 室内看展"],
  "sunsetEvaluation": {
    "recommended": true,
    "sunsetTime": "18:05",
    "goldenHourWindow": "17:35 - 18:20",
    "cloudCover": 40,
    "rainProb": 10,
    "recommendationReason": "今日多云微晴，晚霞概率高",
    "recommendedSpot": "Observatory Hill Park",
    "photoTip": "建议提前 20 分钟到场，用海港大桥作前景压住画面右侧……"
  }
}
```

**这里有三个字段是大模型"没有资格"决定的**，全部由后端算完覆盖写入：`parkingInfo`、`sunsetEvaluation`，以及受 `sunsetEvaluation` 影响的日落节点。这是整个项目最核心的设计思想：**把确定性的事情交给代码，把创造性的事情交给模型。**

---

## 2. 先补齐 8 个基础概念

面试官问到 Spring AI 项目时，下面这 8 个词几乎必然出现。先搞懂它们，后面看代码才不会懵。

### 2.1 LLM（大语言模型）

就是 GPT / Gemini / 通义千问这类模型。你给它一段文字（**Prompt，提示词**），它返回一段文字。它本身**没有记忆、不能上网、不知道今天几号**。

本项目用的是 **Google Gemini**，但接入方式很巧妙——走的是 **OpenAI 兼容端点**：

```yaml
spring:
  ai:
    openai:
      base-url: https://generativelanguage.googleapis.com/v1beta/openai/
      chat:
        options:
          model: gemini-3.6-flash
```

Gemini 官方提供了一个"假装自己是 OpenAI"的 API 地址。所以我们用 Spring AI 的 **OpenAI Starter**，只是把 `base-url` 指向 Google。

> **面试可以这么说**：这样做的好处是不绑定厂商。换成 DeepSeek、Kimi、通义千问任何一个提供 OpenAI 兼容协议的模型，只需要改 `base-url` 和 `model` 两行配置，Java 代码一行不用动。

### 2.2 Prompt（提示词）与 System / User 消息

一次对话通常有两种角色的消息：

- **System Message（系统提示词）**：给模型设定身份和规则，用户看不到。比如"你是一个专业的行程规划专家，必须考虑地理邻近度"。
- **User Message（用户提示词）**：用户实际输入的内容。

本项目的核心技巧叫 **动态提示词工程（Dynamic Prompt Engineering）**：System Message 不是写死的，而是**每次请求都根据实时数据重新拼装**。比如今天天气好，就往里面塞一段"强制安排落日打卡点"；天气不好就塞"不要安排落日"。

### 2.3 结构化输出（Structured Output）

模型天生输出文本。但我们要的是 JSON 对象，前端才好渲染。

Spring AI 提供了一个魔法方法：

```java
ItineraryPlan plan = chatClient.prompt()
        .system(systemMessage)
        .user(message)
        .call()
        .entity(ItineraryPlan.class);   // ← 关键
```

`.entity(ItineraryPlan.class)` 背后做了三件事：

1. 用 **JSON Schema 生成器**（本项目引入了 `com.github.victools:jsonschema-generator`）读取 `ItineraryPlan` 这个类的字段结构，生成一份 JSON Schema。
2. 把这份 Schema 悄悄拼进发给模型的提示词里，告诉模型"你必须按这个格式输出"。
3. 拿到模型返回的文本后，用 Jackson 反序列化成 Java 对象。

这就是 **BeanOutputConverter** 的工作原理。理解这一点很重要，因为它解释了为什么 DTO 的字段名和注释会影响模型输出质量——**字段名本身就是提示词的一部分**。

### 2.4 RAG（检索增强生成）

全称 Retrieval-Augmented Generation。一句话：**先查资料，再让模型基于资料回答**。

为什么需要它？因为模型不知道"Single O Surry Hills"这家店，硬问它就会**幻觉（Hallucination）**，编一个不存在的店名。

标准 RAG 流程是：
1. 把文档切块 → 调 Embedding 模型转成向量 → 存进向量数据库
2. 用户提问 → 问题也转成向量 → 在向量库里找最相似的几条
3. 把找到的内容拼进提示词

**本项目做了简化**：因为 Gemini 的 OpenAI 兼容端点不支持 embedding 路由（会 404），我们把向量检索降级成了**内存关键词加权评分检索**。详见 [6.2](#62-私域-poi-检索rag-的降级实现)。

### 2.5 Tool Calling（工具调用 / Function Calling）

模型不能上网、不能读文件、不能算数。**Tool Calling** 让模型可以"申请"调用你写的 Java 方法。

流程是：
1. 你把方法用 `@Tool` 标注，Spring AI 把方法签名和描述发给模型
2. 模型觉得需要天气数据时，不直接回答，而是返回一个"我要调用 getWeather('Sydney','今日')"的请求
3. 你的程序真的执行这个方法，把结果再发回给模型
4. 模型基于真实结果生成最终回答

本项目的例子：

```java
@Tool(description = "查询指定城市或区域在特定日期的天气情况与出行适宜度")
public WeatherInfo getWeather(
        @ToolParam(description = "城市或区域名称，如 Sydney, Kingsford, CBD") String location,
        @ToolParam(description = "查询日期或时段，如 今日, 明天, 上午") String date) { ... }
```

### 2.6 MCP（Model Context Protocol）

Anthropic 提出的标准协议，可以理解为"**Tool Calling 的 USB 接口标准**"。

Tool Calling 的工具必须写在你自己的项目里。MCP 则让工具可以是**独立进程甚至独立服务器**，通过标准协议对接。这样社区可以共享工具（比如高德地图 MCP Server、GitHub MCP Server），你只要在配置文件里声明就能用。

本项目的 `mcp-servers.json` 声明了两个：

```json
{
  "mcpServers": {
    "amap-maps": { "command": "npx.cmd", "args": ["-y", "@amap/amap-maps-mcp-server"] },
    "yeah-image-search-mcp-server": { "command": "java", "args": ["-jar", "..."] }
  }
}
```

第二个就是本仓库里的子模块 `yu-image-search-mcp-server`（一个调 Pexels 搜图的 MCP 服务）。

> ⚠️ **当前状态**：`application.yml` 里的 MCP 客户端配置是**注释掉的**，所以 MCP 目前没有真正启用。面试时要如实说明"已实现但默认关闭，避免启动依赖外部进程"。

### 2.7 Agent（智能体）与 ReAct 模式

普通的一问一答是"单轮"。**Agent** 是让模型能自主循环：思考 → 调工具 → 看结果 → 再思考 → 再调工具……直到任务完成。

最经典的范式叫 **ReAct = Reasoning（推理）+ Acting（行动）**。

本项目实现了一套完整的 Agent 框架（`agent` 包），详见 [6.6](#66-agent-框架与-reactx-模式)。

### 2.8 Advisor（顾问 / 拦截器）

Spring AI 的 **Advisor** 相当于 Spring MVC 的拦截器，或者 AOP 的切面。它能在请求发给模型前、响应回来后插入逻辑。

本项目有两个自定义 Advisor：
- `MyLoggerAdvisor`：打印请求和响应日志
- `ReReadingAdvisor`：实现 Re2 论文的"重读提示词"技巧（默认关闭）

还用了一个官方的：
- `MessageChatMemoryAdvisor`：自动把历史对话塞进请求，实现多轮记忆

---

## 3. 技术栈全清单

### 3.1 后端主项目

| 技术 | 版本 | 在本项目干什么 |
|---|---|---|
| **Java** | 21 | 语言。用到了 `record`、文本块 `"""`、`switch` 表达式等新特性 |
| **Spring Boot** | 3.4.4 | 应用框架、自动配置、内嵌 Tomcat |
| **Spring AI** | 1.0.0 | 核心。`ChatClient`、结构化输出、`ChatMemory`、Advisor、Tool Calling |
| **Spring AI OpenAI Starter** | 1.0.0 | 通过 OpenAI 兼容协议接 Gemini |
| **Spring AI MCP Client** | 1.0.0 | MCP 协议客户端（默认关闭） |
| **Spring Web MVC** | - | REST 接口、SSE 流式输出 |
| **Spring Cache + Caffeine** | - | 气象数据本地缓存（30 分钟 / 最多 100 条） |
| **RestClient** | Spring 6.2 | 同步 HTTP 客户端，调 Open-Meteo 和 Google Places |
| **iText 9** | 9.1.0 | PDF 生成与排版引擎 |
| **Jackson** | - | JSON 序列化/反序列化 |
| **Lombok** | 1.18.36 | `@Data`、`@Slf4j` 减少样板代码 |
| **Hutool** | 5.8.37 | 工具类（文件操作、字符串判空、HTTP） |
| **Knife4j** | 4.4.0 | 增强版 Swagger UI，生成 API 文档 |
| **jsonschema-generator** | 4.38.0 | 从 Java 类生成 JSON Schema，支撑结构化输出 |
| **Kryo** | 5.6.2 | 高性能序列化，用于文件版对话记忆 |
| **jsoup** | 1.19.1 | HTML 解析（网页抓取工具） |
| **JUnit 5 + AssertJ + Mockito** | - | 单元测试（`spring-boot-starter-test` 自带） |
| **MockRestServiceServer** | Spring Test | 拦截 HTTP 层，实现零网络测试 |

### 3.2 预留但未启用

| 技术 | 状态 |
|---|---|
| PgVector (PostgreSQL 向量扩展) | 依赖已引入，配置注释掉，`DataSourceAutoConfiguration` 被排除 |
| LangChain4J DashScope | 依赖已引入，代码在 `demo_backup/` |
| Ollama（本地模型） | 配置注释掉 |
| Embedding 模型 | 显式关闭 `spring.ai.model.embedding: none` |

### 3.3 前端与子模块

| 模块 | 技术 | 状态 |
|---|---|---|
| `yu-ai-agent-frontend` | Vue 3 + Vite 4 + vue-router + axios | **模板遗留**，页面还是 LoveMaster/SuperAgent，未适配行程功能 |
| `yu-image-search-mcp-server` | Spring Boot + Spring AI MCP Server + Pexels API | 独立可运行的 MCP 服务，支持 stdio / SSE 两种传输 |

---

## 4. 项目结构逐目录讲解

```
yu-ai-agent-master/
├── pom.xml                      Maven 配置：依赖、Java 21、Lombok 注解处理器
├── src/main/java/com/yeah/yeahaiagent/
│   ├── YeahAiAgentApplication.java   启动类（排除了数据源自动配置）
│   ├── advisor/                 Spring AI 拦截器
│   ├── agent/                   自研 Agent 框架（ReAct 循环）
│   ├── app/                     业务编排层 ★核心
│   ├── chatmemory/              对话记忆的文件持久化实现
│   ├── config/                  Spring 配置类
│   ├── constant/                常量
│   ├── controller/              REST 接口层
│   ├── model/dto/               数据传输对象
│   ├── model/enums/             枚举
│   ├── service/                 业务服务层 ★核心
│   └── tools/                   Tool Calling 工具 + PDF 排版引擎
├── src/main/resources/
│   ├── application.yml          主配置
│   ├── application-prod.yml     生产配置（空模板）
│   └── mcp-servers.json         MCP 服务声明
├── src/test/java/...            单元测试（3 个类 17 个用例）
├── demo_backup/ rag_backup/ test_backup/   模板遗留代码，不参与编译
├── yu-ai-agent-frontend/        Vue 前端（模板遗留）
└── yu-image-search-mcp-server/  MCP 图片搜索服务子模块
```

### 4.1 `controller` — 接入层

| 类 | 路由前缀 | 职责 |
|---|---|---|
| `AiController` | `/ai/itinerary` | 行程规划、Tool Calling 对话、SSE 流式、PDF 导出、Manus 智能体 |
| `WeatherController` | `/ai/weather` | 落日评估独立查询（便于 Knife4j 单测） |
| `PlacesController` | `/ai/places` | 停车查询独立接口 |
| `HealthController` | `/health` | 健康检查，返回 `"ok"` |

> ⚠️ 注意 `application.yml` 里配置了 `server.servlet.context-path: /api`，所以真实访问路径要加 `/api` 前缀，例如 `/api/ai/itinerary/plan`。

**`AiController` 的 5 个接口：**

| 方法 | 路由 | 说明 |
|---|---|---|
| GET | `/plan` | ★主接口。结构化行程规划，参数 `message` / `chatId` / `lang` |
| GET | `/tools` | 走 Tool Calling 的自由对话 |
| GET | `/stream` | SSE 流式输出（`text/event-stream`） |
| POST | `/export-pdf` | 接收前端回传的行程 JSON，生成 PDF 文件流 |
| GET | `/manus/chat` | 启动 ReAct 智能体，SSE 推送每一步 |

PDF 导出接口有个细节值得讲——**中文文件名的 HTTP 头编码**：

```java
String asciiName  = chinese ? "itinerary-zh.pdf" : "itinerary-en.pdf";
String displayName = chinese ? "悉尼行程单.pdf" : "sydney-itinerary.pdf";
String encodedName = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
.header(HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName)
```

HTTP 头只能放 ASCII，中文文件名必须走 RFC 5987 的 `filename*=UTF-8''` 语法。同时保留一个纯 ASCII 的 `filename=` 作为老浏览器兜底。`URLEncoder` 会把空格编成 `+`，但 RFC 要求 `%20`，所以要手工替换。

### 4.2 `app` — 业务编排层

只有一个类 `ItineraryApp`，是整个项目的**大脑**。它：
- 持有配置好的 `ChatClient`
- 注入 `PoiRagService` / `WeatherService` / `ParkingService`
- 负责**动态拼装 System Prompt**
- 调用模型拿到结构化结果后**做数据覆盖**

详细流程见第 5 章。

### 4.3 `service` — 业务服务层

| 类 | 职责 |
|---|---|
| `PoiRagService` | 私域打卡点库 + 关键词加权检索（RAG 的降级实现） |
| `WeatherService` | 落日决策引擎（判定逻辑 + 中英文文案） |
| `OpenMeteoWeatherService` | 气象数据访问层（HTTP 调用 + Caffeine 缓存） |
| `ParkingService`（接口） | 停车查询契约 |
| `impl/MockParkingService` | 本地零成本实现，内置 9 个悉尼真实停车场 |
| `impl/GooglePlacesParkingService` | Google Places API (New) 实现，失败自动降级 |
| `ItineraryExportService` | PDF 渲染编排（标题/摘要/时间轴/落日卡片/备选） |

### 4.4 `model` — 数据模型

| 类 | 类型 | 说明 |
|---|---|---|
| `ItineraryPlan` | Lombok `@Data` 类 | 行程根对象。**可变**，因为后端要覆盖写入 `sunsetEvaluation` |
| `ItineraryNode` | Lombok `@Data` 类 | 单个时间轴节点。**可变**，因为要挂载 `parkingInfo` |
| `ParkingInfo` | `record` | 停车信息，**不可变**（纯数据，无需修改） |
| `SunsetEvaluation` | `record` | 落日评估结果，**不可变** |
| `ItineraryRequest` | Lombok `@Data` 类 | 请求 DTO（当前 Controller 用的是 `@RequestParam`，这个类暂未直接使用） |
| `LanguageEnum` | `enum` | `ZH` / `EN`，带 `fromCode()` 容错解析和 `text(zh, en)` 二选一helper |

> **为什么有的用 record 有的用 class？** `record` 是 Java 16+ 的不可变数据载体，字段 final、自动生成 `equals`/`hashCode`/`toString`。`ItineraryPlan` 和 `ItineraryNode` 需要被后端 `setXxx()` 覆盖，所以必须是可变类；`ParkingInfo` 和 `SunsetEvaluation` 生成后不再修改，用 record 更安全、代码更少。**这是一个很好的面试回答点。**

### 4.5 `config` — 配置类

| 类 | 职责 |
|---|---|
| `CacheConfig` | `@EnableCaching` + Caffeine `weatherCache`（30 分钟过期，最多 100 条） |
| `WeatherRestClientConfig` | Open-Meteo 专用 `RestClient`：连接超时 2s、读取超时 3s |
| `ParkingServiceConfig` | 按 `parking.provider` 配置在 mock / google 两个实现间切换 |
| `CorsConfig` | 全局跨域，`allowedOriginPatterns("*")` + `allowCredentials(true)` |

### 4.6 `tools` — 工具层

**重要：这里有两类东西，别混淆。**

**(a) 真正注册给模型的工具**（`ToolRegistration` 里的 3 个）：

```java
@Bean
public ToolCallback[] allTools() {
    return ToolCallbacks.from(
            new WeatherTool(),          // 查天气
            new DistanceMatrixTool(),   // 算两点通行时间
            new TerminateTool()         // 告诉 Agent 任务结束
    );
}
```

**(b) 存在但未注册的工具**（模板遗留 + 内部使用）：

`FileOperationTool`、`WebSearchTool`、`WebScrapingTool`、`ResourceDownloadTool`、`TerminalOperationTool`、`PDFGenerationTool`。

其中 **`PDFGenerationTool` 很特殊**：它虽然有 `@Tool` 注解，但没有注册给模型；项目实际把它当作**PDF 排版设计系统的静态工具类**使用——`ItineraryExportService` 调用它的 `loadFontPair()`、`sanitize()` 和一整套颜色常量。

> **面试注意**：如果面试官问"你有多少个工具"，诚实回答"注册给模型的有 3 个，另外有一批模板自带的工具类未启用，其中 PDF 工具被改造成了排版引擎"。说得越准确越可信。

### 4.7 `agent` — 自研 Agent 框架

```
BaseAgent (抽象类)
   └── ReActAgent (抽象类，定义 think/act)
          └── ToolCallAgent (实现 think/act)
                 └── YuManus (具体智能体)
```

详见 [6.6](#66-agent-框架与-reactx-模式)。

### 4.8 `*_backup` 目录

`demo_backup/`、`rag_backup/`、`test_backup/` 是原始模板（"AI 恋爱大师"项目）的代码，**被移出了 `src/` 所以不参与编译**。保留是为了参考，但面试时如果被翻到，要能解释清楚这是模板遗留。

---

## 5. 核心主链路：一次请求的完整旅程

这是**面试最高频的问题**："讲一下你项目从用户输入到返回结果的完整流程"。下面按 10 步拆解 `GET /api/ai/itinerary/plan`。

### Step 0：请求进入

```java
@GetMapping("/plan")
public ItineraryPlan planItinerary(
        @RequestParam String message,
        @RequestParam(required = false) String chatId,
        @RequestParam(required = false, defaultValue = "zh") String lang) {
    return itineraryApp.doPlanItinerary(message, chatId, lang);
}
```

Controller 非常薄，只做参数接收和透传。`lang` 默认 `zh`。

### Step 1：解析语言

```java
LanguageEnum language = LanguageEnum.fromCode(lang);
```

`fromCode` 做了容错：`null`、空串、无法识别 → 一律回落 `ZH`；只要以 `en` 开头（`en`、`EN`、`en-US`）→ `EN`。

### Step 2：选择基础 System Prompt

```java
String systemMessage = language.text(SYSTEM_PROMPT_ZH, SYSTEM_PROMPT_EN) + ...
```

`SYSTEM_PROMPT_ZH` 定义了 5 条核心规则，其中第 4 条最关键：

> 【动态局部修正规则】：当用户要求更换某个节点时，只能精准替换目标节点或给出 2 个室内备选方案，**禁止无故重算并推翻其他无冲突的既定行程节点**。

这条规则解决了一个真实痛点：多轮对话中用户说"第二家换一下"，模型往往会把整个行程重新生成一遍，用户体验极差。

### Step 3：RAG 检索，注入私域候选池

```java
String ragResult = poiRagService.searchPoi(message);
if (ragResult != null && !ragResult.isBlank()) {
    systemMessage += "\n【私藏打卡候选池】：……请优先参考并选择这些地点编排行程：\n" + ragResult;
}
```

`searchPoi` 对本地 4 个 POI 做加权评分，返回 Top 3 的格式化文本（含店名、类别、标签、氛围与摄影属性、打卡小贴士）。

**这一步是抑制模型幻觉的关键**：有了真实候选池，模型就不会去编造不存在的店。

### Step 4：调用落日决策引擎

```java
SunsetEvaluation sunsetEvaluation = weatherService.evaluateTodaySunset(language.getCode());
```

内部链路：`WeatherService` → `OpenMeteoWeatherService.queryDailyForecast()`（带 `@Cacheable`）→ 命中缓存就直接返回，否则发 HTTP 请求（2s/3s 超时）。

### Step 5：根据天气动态注入不同的规则

```java
systemMessage += "\n" + buildSunsetRule(sunsetEvaluation, language);
```

这是**提示词工程的精华**，两个分支的提示词完全相反：

- **天气好**（`recommended == true`）：
  > 【日落机位激活】今日天气极佳（日落时间 18:05，最佳观赏窗口 17:35 - 18:20）。请在下午最后一个行程节点，**强制安排**落日打卡点（推荐 Observatory Hill Park），将其无缝拼接进 schedule 时间轴的终点！

- **天气差**（`recommended == false`）：
  > 【天气不佳提示】今日日落条件欠佳（原因：今日降水概率约 60%……）。请**不要**在 schedule 中强行安排落日户外点，而是在 backupSuggestions 列表中贴心提醒用户今日不适宜追日落。

### Step 6：识别自驾场景

```java
if (isDrivingScenario(message)) {
    systemMessage += "\n【自驾场景】……不要编造具体停车场名称或评分，停车信息将由服务端按节点挂载。";
}
```

`isDrivingScenario` 是个简单的关键词匹配（自驾/开车/停车/drive/parking/driving）。

**注意最后半句**——这是一个**反幻觉指令**：明确告诉模型"停车的事你别管，我来填"。

### Step 7：注入语言锁

```java
systemMessage += "\n" + buildLanguageLock(language);
```

中文模式下会追加：

> 【语言约束·强制中文】本次请求 lang=zh。你必须让 JSON 里每一个字符串字段都使用简体中文，包括但不限于 title、summary、category……**禁止中英夹杂**。仅店名、路名、街区等官方专有名词可保留英文原名。

为什么需要这个？因为私域库里的描述是中文的，模型很容易在英文模式下"抄"中文进去。语言锁是最后一道防线。

### Step 8：调用大模型，拿结构化结果

```java
ItineraryPlan itineraryPlan = chatClient
        .prompt()
        .system(systemMessage)                                       // 动态拼装的完整提示词
        .user(message)                                               // 用户原话
        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))  // 挂上会话记忆
        .call()
        .entity(ItineraryPlan.class);                                // 结构化输出
```

这一行 `.entity()` 背后：生成 JSON Schema → 拼进提示词 → 调 Gemini → Jackson 反序列化。

同时 `MessageChatMemoryAdvisor` 会自动把这个 `chatId` 下的历史消息塞进请求，实现多轮修正。

### Step 9：后端数据覆盖（最关键的一步）

```java
if (itineraryPlan != null) {
    itineraryPlan.setSunsetEvaluation(sunsetEvaluation);   // 覆盖落日元数据
    attachParking(itineraryPlan, language.getCode());      // 逐节点挂载停车
}
```

`attachParking` 遍历每个节点：

```java
ParkingInfo parkingInfo = parkingService.findNearbyParking(0d, 0d, node.getPoiName(), lang);
node.setParkingInfo(parkingInfo);
```

> **一个值得注意的实现细节**：这里传的经纬度是 `(0, 0)`。`MockParkingService.isValidCoord(0,0)` 返回 `false`，于是服务会走**店名匹配**路径，用内置的 POI 近似坐标表估算距离。这是因为大模型返回的行程节点里没有经纬度字段。

### Step 10：返回 JSON

Spring MVC 用 Jackson 把 `ItineraryPlan` 序列化成 JSON 返回。

### 完整链路图

```
用户 "Surry Hills 咖啡黑胶半日游，我开车"
  │
  ├─▶ AiController.planItinerary(message, chatId, lang="zh")
  │
  └─▶ ItineraryApp.doPlanItinerary()
        │
        ├─ 1. LanguageEnum.fromCode("zh") → ZH
        ├─ 2. 基础 System Prompt (中文版 5 条规则)
        ├─ 3. PoiRagService.searchPoi()  ─────▶ 本地加权评分 → Top3 POI 文本
        ├─ 4. WeatherService.evaluateTodaySunset()
        │       └─ OpenMeteoWeatherService.queryDailyForecast()
        │             ├─ @Cacheable 命中 ──▶ 直接返回（0 次 HTTP）
        │             └─ 未命中 ──▶ RestClient (2s/3s 超时) ──▶ Open-Meteo API
        │                              └─ 异常 ──▶ WeatherService 捕获 ──▶ 兜底对象
        ├─ 5. buildSunsetRule()   → 天气好/坏两套相反提示词
        ├─ 6. isDrivingScenario() → 自驾反幻觉指令
        ├─ 7. buildLanguageLock() → 强制单语言输出
        │
        ├─ 8. ChatClient.prompt().system(...).user(...).call().entity(ItineraryPlan.class)
        │       ├─ MessageChatMemoryAdvisor 注入历史消息
        │       ├─ MyLoggerAdvisor 打印日志
        │       ├─ BeanOutputConverter 生成 JSON Schema 拼进提示词
        │       └─ HTTP ──▶ Gemini (OpenAI 兼容端点)
        │
        ├─ 9. plan.setSunsetEvaluation(...)      ← 后端数据覆盖模型输出
        │     attachParking(plan, lang)          ← 逐节点挂载真实停车数据
        │
        └─▶ 返回 ItineraryPlan JSON

（可选）前端把这份 JSON POST 回 /export-pdf
  └─▶ ItineraryExportService.exportPlanToPdf(plan, lang)
        ├─ PDFGenerationTool.loadFontPair(language)  → 中文 PingFang / 英文 Helvetica
        ├─ renderTitle / renderSummary / renderSchedule
        ├─ renderSunsetHighlight（recommended 才画橙色高亮卡）
        └─ renderBackupSuggestions
        └─▶ 返回 File ──▶ Controller 包成 InputStreamResource 流式下载
```

---

## 6. 六大核心模块深度讲解

### 6.1 Spring AI 编排层

#### ChatClient 的构建

```java
public ItineraryApp(ChatModel dashscopeChatModel, PoiRagService poiRagService,
                    WeatherService weatherService, ParkingService parkingService) {
    MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)
            .build();
    chatClient = ChatClient.builder(dashscopeChatModel)
            .defaultSystem(SYSTEM_PROMPT_ZH)
            .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build(),
                    new MyLoggerAdvisor()
            )
            .build();
}
```

三个要点：

1. **`dashscopeChatModel` 这个参数名是模板遗留**（原项目用阿里云 DashScope）。Spring 是**按类型注入**的，实际注入的是 `OpenAiChatModel`。名字不影响功能，但面试时如果被问到要能解释。

2. **`MessageWindowChatMemory` + `maxMessages(20)`**：滑动窗口记忆。只保留最近 20 条消息，超出的丢弃。这是必须的——不限制的话 token 会无限增长，成本爆炸且会超出模型上下文窗口。

3. **`InMemoryChatMemoryRepository`**：记忆存在 JVM 内存里，**重启丢失、多实例不共享**。项目里还实现了一个 `FileBasedChatMemory`（用 Kryo 序列化到磁盘），但目前是注释状态。

#### 对话记忆是怎么生效的

```java
.advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
```

每次请求传入 `chatId`，`MessageChatMemoryAdvisor` 就会：
1. 请求前：按 `chatId` 从 `ChatMemory` 取出历史消息，插到当前 prompt 前面
2. 响应后：把本轮的 user + assistant 消息写回 `ChatMemory`

所以用户第二轮说"把第二站换成室内的"，模型能知道"第二站"指的是什么。

#### FileBasedChatMemory（未启用但值得讲）

```java
private static final Kryo kryo = new Kryo();
static {
    kryo.setRegistrationRequired(false);
    kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
}
```

用 Kryo 把 `List<Message>` 序列化成 `{conversationId}.kryo` 文件。

- `setRegistrationRequired(false)`：不用提前注册类，方便但性能略低
- `StdInstantiatorStrategy`（来自 Objenesis）：能实例化**没有无参构造函数**的类，因为 Spring AI 的 `Message` 实现类大多没有无参构造

### 6.2 私域 POI 检索（RAG 的降级实现）

#### 为什么放弃向量检索

原计划是标准 RAG：`SimpleVectorStore` + `text-embedding-004`。但 **Gemini 的 OpenAI 兼容端点不支持 embedding 路由**，启动直接 404。

解决方案分三步：
1. `application.yml` 加 `spring.ai.model.embedding: none`，关掉 Spring AI 的 Embedding 自动配置
2. 删掉 `RagConfig`（原本注册 `SimpleVectorStore` 的配置类）
3. 把 `PoiRagService` 改成纯内存关键词加权检索

> **这是一个很好的面试素材**：说明你能在外部约束下做技术权衡，而不是死磕。可以补一句"对于 4 个 POI 的数据量，向量检索本身就是过度设计；等数据上千再上向量库也不迟"。

#### 数据模型

```java
public record PoiItem(
        String poiName,             // Single O Surry Hills
        String category,            // Brunch/精品咖啡
        List<String> tags,          // [Surry Hills, 手冲咖啡, 街头轻食]
        List<String> vibes,         // [清晨阳光, 社区感, 活力]        ← 氛围标签
        List<String> visualStyle,   // [街边高饱和长椅, 自然光影]      ← 摄影风格
        String personalTip,         // 私藏小贴士
        String description
) {}
```

`vibes` 和 `visualStyle` 是这个项目的**差异化设计**：普通 POI 库只有类别和标签，这里额外建模了"氛围"和"视觉风格"，让用户可以用"我想找 Neo-Soul 氛围、Saul Leiter 色调的地方"这种感性描述来检索。

#### 评分算法

```java
private int score(PoiItem item, String query) {
    int score = 0;
    if (containsIgnoreCase(query, item.poiName()))     score += 8;   // 店名命中权重最高
    if (containsIgnoreCase(query, item.category()))    score += 4;
    if (containsIgnoreCase(query, item.description())) score += 2;
    if (containsIgnoreCase(query, item.personalTip())) score += 2;
    score += matchListScore(query, item.tags(),        5);
    score += matchListScore(query, item.vibes(),       5);
    score += matchListScore(query, item.visualStyle(), 5);
    return score;
}
```

注意方向：是**用户 query 包含关键词**（`query.contains(keyword)`），不是反过来。多标签命中会累加，所以"Surry Hills 黑胶 Soul"能同时命中 3 个标签拿到 15 分。

最后取 `score > 0` 的 Top 3，格式化成模型友好的文本：

```
【店名】The Record Store
【类别】独立唱片店
【标签】Surry Hills, 黑胶, Soul, Funk, Jazz
【氛围与摄影属性】氛围：Neo-Soul、复古律动、沉浸淘盘；摄影：黑胶封套墙、室内暖调反差
【打卡小贴士】Soul/R&B 专区经常能翻到罕见的黑胶盘……
```

末尾还会追加一句给模型的元指令：

> 若选用了带有打卡小贴士的地点，请在行程建议（summary 或 backupSuggestions）中自然融入该特色

### 6.3 天气与落日决策引擎

#### 数据源

**Open-Meteo**，免费、无需 API Key：

```
https://api.open-meteo.com/v1/forecast
  ?latitude=-33.8688&longitude=151.2093
  &daily=sunset,precipitation_probability_max,cloudcover_mean
  &timezone=Australia/Sydney&forecast_days=1
```

#### 一个踩过的坑：URL 编码

最早用字符串拼接 URL，`timezone=Australia%2FSydney` 里的斜杠被 `RestClient` **二次编码**成 `%252F`，接口报 "Invalid timezone"。修复方式是改用 `UriBuilder` 逐个传参：

```java
.uri(uriBuilder -> uriBuilder.path(FORECAST_PATH)
        .queryParam("timezone", "Australia/Sydney")   // 交给 UriBuilder 编码
        .build())
```

#### 决策算法

```java
private static final int RAIN_PROB_LIMIT  = 30;   // 降水概率上限
private static final int CLOUD_COVER_MIN  = 15;   // 云量下限
private static final int CLOUD_COVER_MAX  = 75;   // 云量上限
private static final int GOLDEN_HOUR_BEFORE_MINUTES = 30;
private static final int GOLDEN_HOUR_AFTER_MINUTES  = 15;

boolean recommended = rainProb < RAIN_PROB_LIMIT
        && cloudCover >= CLOUD_COVER_MIN
        && cloudCover <= CLOUD_COVER_MAX;
```

**为什么云量要在 15%~75% 之间？** 这是摄影常识：
- 云量太低（万里无云）→ 没有云承接霞光，天空干干净净反而不好看
- 云量太高（阴天）→ 云层太厚把太阳完全挡住，只会天色渐暗
- 中等云量 → 云被夕阳染成金红色，才是最出片的"火烧云"

**黄金窗口** = 日落前 30 分钟 到 日落后 15 分钟。

不推荐时还会按具体原因给**差异化的委婉文案**：

| 原因 | 文案 | 替代建议 |
|---|---|---|
| 降水概率高 | "雨云容易吸走霞光" | "雨后湿地面反光很适合拍城市倒影" |
| 云量过高 | "阴云偏厚会把霞光整片遮住" | "更适合拍低饱和的冷调建筑几何" |
| 云量过低 | "天空过于通透、缺少承接霞光的云" | "改拍剪影或长焦压缩的城市线条" |

#### Caffeine 缓存

```java
@Bean
public CaffeineCacheManager cacheManager() {
    CaffeineCacheManager cacheManager = new CaffeineCacheManager();
    cacheManager.registerCustomCache(WEATHER_CACHE, Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(100)
            .build());
    return cacheManager;
}
```

```java
@Cacheable(value = CacheConfig.WEATHER_CACHE, key = "#lat + '_' + #lng + '_' + #date")
public DailyForecast queryDailyForecast(double lat, double lng, LocalDate date) { ... }
```

这里有**三个设计决策，都是面试加分项**：

**① 为什么 `OpenMeteoWeatherService` 要单独拆成一个 Bean？**

因为 `@Cacheable` 依赖 **Spring AOP 代理**。如果把缓存方法和调用方写在同一个类里，内部 `this.queryDailyForecast()` 的自调用**会绕过代理**，缓存完全不生效。这是 Spring 缓存/事务最经典的坑。拆成独立 Bean 后，`WeatherService` 通过依赖注入拿到的是代理对象，调用才会被拦截。

**② 为什么缓存的是原始预报，而不是 `SunsetEvaluation`？**

因为 `SunsetEvaluation` 里的 `recommendationReason`、`photoTip` 是**按语言渲染**的。如果直接缓存它，中文请求先落缓存，后面的英文请求就会拿到中文文案。所以缓存层只存与语言无关的 `DailyForecast`（日落时间、云量、降水概率），中英文各自渲染。

**③ 为什么 `try-catch` 放在 `WeatherService` 而不是缓存方法里？**

`@Cacheable` 的规则是：**方法抛异常时不写缓存**。所以让缓存方法把异常抛出去，上层再捕获降级。这样 Open-Meteo 挂掉时，失败结果不会被固化 30 分钟，下次请求仍会重试。如果在缓存方法内部就 catch 掉返回 null/兜底对象，那这个坏结果就会被缓存半小时。

#### 超时防御

```java
SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
requestFactory.setConnectTimeout(Duration.ofSeconds(2));
requestFactory.setReadTimeout(Duration.ofSeconds(3));
```

**为什么必须设超时？** Spring MVC 是**同步阻塞**模型，每个请求占用一个 Tomcat 工作线程。如果 Open-Meteo 挂了且没有超时，线程会一直挂着。Tomcat 默认 200 个线程，几百个并发请求就能把线程池打满，**整个应用不可用**——这就是"雪崩"。

注意这个超时**只挂在气象客户端上**，用的是 `restClientBuilder.clone()`，不会影响 Google Places 和 Spring AI 的调用。

#### 降级兜底

```java
private SunsetEvaluation fallback(LanguageEnum language) {
    return new SunsetEvaluation(false, null, null, null, null,
            language.text("暂时拿不到实时天气数据，建议出发前再确认当日云量与降水情况",
                          "Live weather is unavailable. Recheck cloud cover and rain before you head out."),
            SUNSET_SPOT,
            language.text("若临时决定前往，可留出日落前后各半小时的弹性时间。", "..."));
}
```

关键在于**降级不影响主流程**——天气服务挂了，行程照样能规划出来，只是少了落日推荐。这叫 **graceful degradation（优雅降级）**。

### 6.4 智能停车服务

#### 接口 + 双实现 + 配置化路由

这是一个标准的**策略模式（Strategy Pattern）+ 适配器**设计：

```java
public interface ParkingService {
    ParkingInfo findNearbyParking(double lat, double lng, String poiName, String lang);
}
```

```java
@Configuration
public class ParkingServiceConfig {
    @Bean
    @Primary
    public ParkingService parkingService(
            @Value("${parking.provider:mock}") String provider,
            MockParkingService mockParkingService,
            GooglePlacesParkingService googlePlacesParkingService) {
        return "google".equalsIgnoreCase(provider) ? googlePlacesParkingService : mockParkingService;
    }
}
```

`@Primary` 保证注入 `ParkingService` 时优先选这个工厂产出的 Bean（否则两个实现类都是 `@Service`，会报"有多个候选 Bean"）。

#### MockParkingService：零成本本地实现

内置 **9 个悉尼真实停车场**（Wilson Parking Market City、Secure Parking Goulburn St、Citipark Surry Hills、The Clocktower、Circular Quay、Broadway、Newtown Council Car Park、ICC Darling Harbour、Riley St），每个带：

```java
new ParkingLot(
        "Wilson Parking - Citipark Surry Hills",
        "2-38 Baptist St, Surry Hills NSW 2010",
        -33.8868, 151.2112, 4.0,
        List.of("surry hills", "single o", "record store", "baptist", "crown"),  // 别名
        "距离 Crown St / Reservoir St 咖啡带步行很近，周末建议 10 点前到",        // 中文贴士
        "Short walk to the Crown St / Reservoir St cafe strip. Arrive before 10am on weekends." // 英文贴士
)
```

#### 评分公式

```java
double distanceScore = 1000d / (meters + 80d);      // 距离越近分越高，1km 外快速衰减
double nameScore = 0;
if (店名互相包含)  nameScore += 8;
for (别名 : 别名列表) if (互相包含) nameScore += 10;
return distanceScore + nameScore;
```

`+80` 是**平滑项**，防止距离趋近 0 时分数爆炸到无穷大。

#### 一个踩过的坑：空格导致匹配失败

用户传 `poiName=SurryHills`（无空格），但别名是 `"surry hills"`（有空格），`contains` 匹配不上。修复方案是引入 `compact()`：

```java
private static String compact(String value) {
    return normalize(value).replace(" ", "");   // 小写 + trim + 去空格
}
```

查询和别名都压缩后再比，`"surryhills"` 就能匹配 `"surryhills"` 了。

#### Haversine 公式（球面距离）

```java
static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
    double earthRadius = 6_371_000d;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return 2 * earthRadius * Math.asin(Math.sqrt(a));
}
```

**为什么不能用勾股定理？** 因为地球是球面，经纬度是角度不是平面坐标。而且**经度 1 度对应的实际距离随纬度变化**（赤道最宽，两极收敛到 0）。Haversine 通过球面三角计算大圆距离，误差在几十米内，对步行估算完全够用。

步行时间按 **80 米/分钟** 换算，且最少显示 1 分钟：

```java
int minutes = Math.max(1, (int) Math.round(meters / WALKING_METERS_PER_MINUTE));
```

#### GooglePlacesParkingService：商业版预留

完整实现了 Google Places API (New) 的调用：

```java
private static final String SEARCH_NEARBY_PATH = "/v1/places:searchNearby";
private static final String SEARCH_TEXT_PATH   = "/v1/places:searchText";
private static final String FIELD_MASK =
        "places.displayName,places.formattedAddress,places.location,places.rating";
```

**FieldMask 是 Google Places API (New) 的核心概念**——必须在请求头 `X-Goog-FieldMask` 里声明你要哪些字段，**按字段计费**。不声明会直接报错，声明太多会多花钱。

三级降级策略：
1. 先 `searchNearby`（800m 半径，按距离排序）
2. 没结果就 `searchText`（"parking near {poiName} Sydney"）
3. 还没有 / API Key 没配 / 任何异常 → **降级到 `MockParkingService`**

```java
if (apiKey.isBlank()) {
    log.info("未配置 parking.google.api-key，降级使用本地停车场数据");
    return mockParkingService.findNearbyParking(lat, lng, poiName, lang);
}
```

### 6.5 PDF 导出与排版引擎

#### 整体设计

分两层：
- **`PDFGenerationTool`** = 设计系统（Design System）：字体加载 + 颜色常量 + 字符降级
- **`ItineraryExportService`** = 渲染编排：用设计系统的原语拼出版面

#### 颜色体系

`PDFGenerationTool` 定义了 20+ 个颜色常量，全部按语义命名：

```java
public static final DeviceRgb COLOR_INK            = new DeviceRgb(33, 37, 41);   // 主文字·深黑
public static final DeviceRgb COLOR_TIME           = new DeviceRgb(30, 64, 175);  // 时间徽章·深靛蓝
public static final DeviceRgb COLOR_TIME_BG        = new DeviceRgb(219, 234, 254);// 时间徽章底色
public static final DeviceRgb COLOR_SLATE          = new DeviceRgb(71, 85, 105);  // 次级文字·石板灰
public static final DeviceRgb COLOR_TRANSIT_BG     = new DeviceRgb(240, 253, 244);// 通勤框·薄荷绿底
public static final DeviceRgb COLOR_TRANSIT_TEXT   = new DeviceRgb(20, 83, 45);   // 通勤框·深森林绿
public static final DeviceRgb COLOR_PARKING_LABEL  = new DeviceRgb(29, 78, 216);  // 停车标签·深蓝
public static final DeviceRgb COLOR_PARKING_RATING = new DeviceRgb(217, 119, 6);  // 评分·琥珀黄
public static final DeviceRgb COLOR_SUNSET_BORDER  = new DeviceRgb(245, 158, 11); // 落日卡片·暖橙边框
```

#### 中文字体：最难啃的骨头

**问题 1：`Type of font STSongStd-Light is not recognized`**

iText 的亚洲字体包 `font-asian` 在 pom 里是 `<scope>test</scope>`，运行时拿不到，导致内置中文字体不可用。

**解决方案**：改用 macOS 系统字体，按优先级探测：

```java
private static final List<String> FONT_FAMILY_FILES = List.of(
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
        "/Library/Fonts/Arial Unicode.ttf",
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
        "/System/Library/Fonts/Supplemental/Songti.ttc"
);
```

**问题 2：字体文件存在但渲染不出中文**

`STHeiti Light.ttc` 能加载成功，但实际渲染出空白。所以加载后必须**验证字形**：

```java
private static final String CJK_PROBE_CHARS   = "悉行程类停";      // 中文探针
private static final String LATIN_PROBE_CHARS = "Sa0&:/（，。";    // 拉丁+数字+标点探针
```

只有**两组探针字符都能取到字形**的字体才算可用。

**问题 3：中英文粗细不统一**

如果中文用 PingFang、英文回退到 Helvetica，同一行里笔画粗细会明显不一致，很难看。解决方案是要求字体**同时覆盖 CJK 和 Latin**（这就是 `LATIN_PROBE_CHARS` 的作用）。

**问题 4：假粗体 vs 真粗体**

iText 9 的 `simulateBold()` 是用描边模拟的假粗，效果生硬。正确做法是从同一个 `.ttc` 字体集合里加载**真实的粗体字面**：

```java
private static final int MAX_TTC_FACE_INDEX = 9;   // ttc 内最多探测 10 个字面
private static final List<String> BOLD_NAME_MARKERS =
        List.of("semibold", "demibold", "medium", "bold", "-w6", "heavy");
```

`.ttc` 是 TrueType Collection，一个文件里打包多个字重。但**不同 macOS 版本的字重顺序不一样**，不能写死索引，只能遍历探测并按名称特征判断哪个是粗体。

最终封装成：

```java
public record CjkFontPair(PdfFont regular, PdfFont bold) {}
```

**问题 5：emoji 渲染成豆腐块**

中文字体基本不含 emoji 字形，直接渲染 `🕒` 会变成空白方块（"豆腐"）。解决方案是 `sanitize()`：

```java
public static String sanitize(String text, PdfFont font) {
    // 逐个 codePoint 检查：
    //   字体有这个字形 → 保留
    //   没有 → 查降级表，换成几何符号（如 🕒 → ◷）
    //   降级符号也没有 → 丢弃
}
```

**问题 6：英文 PDF 的跨平台兼容**

英文模式下不依赖系统 CJK 字体，直接用 iText 内置的 Helvetica：

```java
public static CjkFontPair loadFontPair(LanguageEnum language) {
    if (language != null && !language.isZh()) {
        return loadLatinFontPair();     // Helvetica / Helvetica-Bold
    }
    return loadCjkFontPair();
}
```

**性能优化**：字体解析很慢，所以缓存解析结果：

```java
private static volatile FontSpecPair cachedSpecPair;
```

注意缓存的是**字体规格（文件路径 + 索引）**而不是 `PdfFont` 对象——因为 `PdfFont` 与具体的 `PdfDocument` 绑定，**不能跨文档复用**。这个细节很容易踩坑。

#### 版面结构

`ItineraryExportService` 渲染 5 个模块：

| 模块 | 方法 | 样式 |
|---|---|---|
| 品牌标头 + 主标题 | `renderTitle` | 居中，20pt 粗体，下方 60% 宽灰色分割线 |
| 行程摘要 | `renderSummary` | 全宽淡色卡片，10pt 炭灰 |
| 时间轴节点 | `renderSchedule` → `buildNodeCard` | 每个 POI 一张卡片 |
| 落日高亮卡 | `renderSunsetHighlight` | 仅 `recommended==true` 时渲染，暖橙边框 |
| 备选建议 | `renderBackupSuggestions` | 带圆点列表 |

每张节点卡片内部分 5 行：时间徽章+店名 → 类别+营业时间 → 建议停留 → 下一程通勤（薄荷绿框）→ 私藏笔记（暖黄块），再加一张停车信息卡。

#### 分页控制

```java
.setKeepWithNext(true)    // 标题必须和后面的内容在同一页
```

用在标题和小节标题上，避免"孤儿标题"——标题孤零零留在页面底部，内容翻到下一页。

反过来，节点卡片**故意不设** `setKeepTogether(true)`，允许大卡片自然跨页，否则会产生大片空白。

#### 颜色继承的坑

iText 的 `Paragraph` 会继承父 `Div` 的颜色。之前出现过文字发灰看不清的问题，修复方式是**每个 `Text`/`Paragraph` 都显式设置颜色**：

```java
private record Styler(CjkFontPair fonts) {
    Text boldText(String content, DeviceRgb color) {
        return new Text(PDFGenerationTool.sanitize(content, fonts.bold()))
                .setFont(fonts.bold())
                .setFontColor(color);      // ← 显式设色，不依赖继承
    }
}
```

### 6.6 Agent 框架与 ReAct 模式

#### 三层继承结构

```
BaseAgent          状态机 + 执行循环 + SSE 流式输出
  └── ReActAgent   定义 think() / act() 两个抽象方法，实现 step() = think + act
        └── ToolCallAgent   具体实现 think/act，接管工具调用
              └── YuManus   配置提示词和最大步数
```

#### BaseAgent：状态机与循环

```java
public enum AgentState { IDLE, RUNNING, FINISHED, ERROR }
```

```java
public String run(String userPrompt) {
    if (this.state != AgentState.IDLE) throw new RuntimeException("Cannot run agent from state: " + state);
    this.state = AgentState.RUNNING;
    messageList.add(new UserMessage(userPrompt));
    try {
        for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
            currentStep = i + 1;
            String stepResult = step();            // ← 子类实现
            results.add("Step " + currentStep + ": " + stepResult);
        }
        if (currentStep >= maxSteps) {
            state = AgentState.FINISHED;
            results.add("Terminated: Reached max steps (" + maxSteps + ")");
        }
        return String.join("\n", results);
    } catch (Exception e) {
        state = AgentState.ERROR;
        return "执行错误" + e.getMessage();
    } finally {
        this.cleanup();
    }
}
```

**`maxSteps` 是必须的安全阀**——防止模型陷入无限循环烧钱。

#### SSE 流式版本

```java
public SseEmitter runStream(String userPrompt) {
    SseEmitter sseEmitter = new SseEmitter(300000L);       // 5 分钟超时
    CompletableFuture.runAsync(() -> {
        // ... 同样的循环，每步 sseEmitter.send(result)
    });
    sseEmitter.onTimeout(() -> { this.state = AgentState.ERROR; this.cleanup(); });
    sseEmitter.onCompletion(() -> { ... this.cleanup(); });
    return sseEmitter;
}
```

**为什么要异步？** `SseEmitter` 必须立刻返回给 Spring MVC 释放请求线程，实际工作在另一个线程里做，通过 emitter 持续推送。否则会阻塞 Tomcat 线程。

#### ToolCallAgent：手动接管工具调用

这是整个 Agent 框架**最有技术含量**的地方：

```java
this.chatOptions = OpenAiChatOptions.builder()
        .internalToolExecutionEnabled(false)    // ← 关闭 Spring AI 内置的工具执行
        .build();
```

**为什么要关掉内置机制？** Spring AI 默认会自动帮你执行工具并把结果发回模型，整个过程是黑盒的。关掉之后我们自己控制：

```java
// think()：问模型"下一步要调什么工具"
ChatResponse chatResponse = getChatClient().prompt(prompt)
        .system(getSystemPrompt())
        .tools(availableTools)
        .call().chatResponse();
List<AssistantMessage.ToolCall> toolCallList = chatResponse.getResult().getOutput().getToolCalls();
return !toolCallList.isEmpty();     // 有工具要调就返回 true

// act()：真正执行工具
ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
setMessageList(toolExecutionResult.conversationHistory());   // 更新消息上下文
```

这样做的好处是**每一步都可观测**：可以打日志、可以通过 SSE 推给前端、可以在中途插入人工审核。

#### 终止机制

```java
boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
        .anyMatch(response -> response.name().equals("doTerminate"));
if (terminateToolCalled) {
    setState(AgentState.FINISHED);
}
```

`TerminateTool` 是一个"空工具"——它什么也不做，唯一作用就是让模型有办法说"我完成了"。模型调用它，Agent 就把状态置为 `FINISHED`，循环退出。

**这是 Agent 设计的经典模式**：给模型一个显式的退出信号，而不是靠解析文本猜测。

#### YuManus：具体智能体

```java
this.setName("yuManus");
this.setSystemPrompt("You are YuManus, an all-capable AI assistant...");
this.setNextStepPrompt("Based on user needs, proactively select the most appropriate tool...");
this.setMaxSteps(20);
```

注意 `AiController` 里是**每次请求 new 一个新实例**：

```java
@GetMapping("/manus/chat")
public SseEmitter doChatWithManus(String message) {
    YuManus yuManus = new YuManus(allTools, dashscopeChatModel);
    return yuManus.runStream(message);
}
```

因为 Agent 是**有状态的**（`state`、`currentStep`、`messageList`），共享单例会导致并发请求互相污染。虽然 `YuManus` 标了 `@Component`，但这里刻意绕开了容器手动 new。

### 6.7 国际化（i18n）

#### 设计思路

不用 Spring 的 `MessageSource` + properties 文件，而是用一个极简的枚举：

```java
public enum LanguageEnum {
    EN("en"), ZH("zh");

    public static LanguageEnum fromCode(String lang) {
        if (lang == null || lang.isBlank()) return ZH;
        return lang.trim().toLowerCase(Locale.ROOT).startsWith("en") ? EN : ZH;
    }

    public String text(String zh, String en) {
        return isZh() ? zh : en;
    }
}
```

`text(zh, en)` 这个方法是整个 i18n 方案的核心，用起来非常清爽：

```java
language.text("今日多云微晴，晚霞概率高",
              "Partly cloudy today, high chance of sunset colour")
```

> **为什么不用 properties 文件？** 因为这些文案大多是**动态拼接**的（带 `%d%%` 占位符、跟业务逻辑强耦合），放 properties 里反而割裂了上下文，改一处逻辑要跳两个文件。对于两种语言、几十条文案的规模，就地二选一更易维护。这是一个可以和面试官讨论的权衡点。

#### 三层覆盖

| 层级 | 做法 |
|---|---|
| 服务层文案 | `WeatherService` / `MockParkingService` 所有硬编码文案改为 `language.text(zh, en)` |
| 模型输出 | `buildLanguageLock()` 注入强制语言约束，禁止中英混杂 |
| PDF 界面 | `PdfLabels` record 承载所有 UI 标签，`PdfLabels.of(language)` 返回对应版本 |

还有一个细节——英文 PDF 不渲染中文的私藏贴士：

```java
private boolean shouldRenderPersonalTip(String tip, LanguageEnum language) { ... }
```

因为私域库的 `personalTip` 只有中文，直接渲染到英文 PDF 里会破坏语言一致性。

---

## 7. 配置文件逐段解释

### `application.yml`

```yaml
spring:
  application:
    name: yeah-ai-agent            # 应用名，日志和监控里会显示
  profiles:
    active: local                  # 激活的 profile
  ai:
    model:
      embedding: none              # ★ 关闭 Embedding 自动配置，否则启动时会尝试连 embedding 端点报 404
    openai:
      api-key: <你的 Key>
      base-url: https://generativelanguage.googleapis.com/v1beta/openai/   # ★ 指向 Gemini 兼容端点
      chat:
        options:
          model: gemini-3.6-flash

server:
  port: 8123
  servlet:
    context-path: /api             # ★ 所有接口都会带 /api 前缀

springdoc:
  swagger-ui:
    path: /swagger-ui.html
  group-configs:
    - group: 'default'
      paths-to-match: '/**'
      packages-to-scan: com.yeah.yeahaiagent.controller   # 只扫描 controller 包

knife4j:
  enable: true
  setting:
    language: zh_cn                # Knife4j 界面中文

parking:
  provider: mock                   # ★ 停车数据源开关：mock / google
  google:
    api-key: ${PARKING_GOOGLE_API_KEY:}   # 从环境变量读，没有就空串

logging:
  level:
    org.springframework.ai: DEBUG  # 打开 Spring AI 的详细日志，能看到实际发给模型的完整提示词
```

`${PARKING_GOOGLE_API_KEY:}` 这个语法是 **Spring 占位符 + 默认值**：冒号后面是默认值（这里是空串）。这样本地不配环境变量也能启动。

### 启动类

```java
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class     // ★ 排除数据源自动配置
})
public class YeahAiAgentApplication { ... }
```

因为 pom 里引了 `spring-boot-starter-jdbc` 和 `postgresql`（为 PgVector 预留），Spring Boot 会尝试自动配置数据源，没配 URL 就启动失败。排除掉就能无数据库运行。

### `mcp-servers.json`

MCP 服务的进程启动描述。`stdio` 模式下，Spring AI 会真的用 `command` + `args` **拉起一个子进程**，通过标准输入输出通信。

---

## 8. 测试体系

`src/test/java` 下 3 个测试类，共 **17 个用例，全部离线运行**（`mvn test` 不需要任何外部网络）。

### 8.1 `MockParkingServiceTest`（7 个）

纯单元测试，不启动 Spring 容器。

| 用例 | 验证点 |
|---|---|
| `testRecommendParkingForNewtown` | Newtown 坐标推荐 `Newtown Council Car Park`，步行分钟数 = `max(1, round(米数/80))` |
| `testRecommendByCoordinateOnly` | 不传店名，纯靠坐标也能选出最近车库 |
| `testFallbackToPoiNameWhenCoordinateMissing` | 坐标为 `(0,0)` 时回落到店名匹配 |
| `testEnglishOutput` | `lang=en` 输出纯英文，不含"步行""停车" |
| `testHaversineDistance` | 1 纬度 ≈ 111194.9m（误差 1m 内）；同点距离为 0；对称性 |
| `testFormatWalking` | 中英文文案格式；不足 1 分钟按 1 分钟显示 |
| `testCoordinateValidation` | `(0,0)` 和越界坐标被判为无效 |

### 8.2 `WeatherServiceTest`（6 个）

用 **`MockRestServiceServer`** 在 HTTP 层拦截，模拟 Open-Meteo 的各种响应。

**关键设计：没用 `@SpringBootTest`。** 全量启动会拉起 Spring AI 的 ChatClient 和 MCP 客户端，需要真 API Key 且很慢。改用 `@SpringJUnitConfig` 拼一个最小上下文：

```java
@Configuration
@Import(CacheConfig.class)
static class WeatherTestConfig {
    @Bean RestClient.Builder openMeteoRestClientBuilder() {
        return RestClient.builder().baseUrl(WeatherRestClientConfig.OPEN_METEO_BASE_URL);
    }
    @Bean MockRestServiceServer mockRestServiceServer(RestClient.Builder builder) {
        return MockRestServiceServer.bindTo(builder).build();     // ← 往 builder 上装 mock 请求工厂
    }
    @Bean RestClient openMeteoRestClient(RestClient.Builder builder, MockRestServiceServer server) {
        return builder.build();     // 形参 server 只为保证 bean 创建顺序
    }
    @Bean OpenMeteoWeatherService openMeteoWeatherService(RestClient c) { return new OpenMeteoWeatherService(c); }
    @Bean WeatherService weatherService(OpenMeteoWeatherService s) { return new WeatherService(s); }
}
```

这样既有真实的 Caffeine 缓存和 `@Cacheable` 代理，又完全不碰大模型。

| 用例 | 验证点 |
|---|---|
| `testWeatherCaching` | 期望值设为 `once()`，连调两次 `evaluateTodaySunset`，第二次必须命中缓存（否则 Mock 服务器当场断言失败） |
| `testCacheIsLanguageIndependent` | 中英文各调一次，只回源一次，但文案不同 |
| `testWeatherFallbackOnHttpError` | 返回 500 时不抛异常，降级为兜底对象 |
| `testWeatherFallbackOnTimeout` | `withException(new SocketTimeoutException(...))` 模拟读超时 |
| `testWeatherFallbackOnIncompletePayload` | 返回 `{"daily":{}}` 字段缺失时降级 |
| `testFailureIsNotCached` | 先 500 再成功，两条期望都被消费 → 证明失败没被缓存 30 分钟 |

**注意 `@BeforeEach` 必须手工重置**，因为 Spring 测试上下文在方法间复用：

```java
@BeforeEach
void resetState() {
    server.reset();              // 清空 Mock 期望和已记录请求
    weatherCache().clear();      // 清空 Caffeine 缓存
}
```

### 8.3 `ItineraryExportServiceTest`（4 个）

| 用例 | 验证点 |
|---|---|
| `testExportPdfSuccessfully` | 中文行程 → 字节数 > 1000，前 4 字节 = `%PDF` 魔数 |
| `testExportEnglishPdf` | 英文走 Latin 字体分支，同样产出合法 PDF |
| `testExportTolerateMissingFields` | 全空字段的残缺行程不崩溃 |
| `testExportRejectNullPlan` | `plan == null` 抛 `IllegalArgumentException` |

脱离 Spring 容器，手动触发 `@PostConstruct`：

```java
PoiRagService poiRagService = new PoiRagService();
poiRagService.initMockData();        // 手动调用，因为没有容器帮你调
exportService = new ItineraryExportService(poiRagService);
```

### 8.4 测试哲学

这套测试有意**没有 mock 业务协作者**。`MockRestServiceServer` 在 HTTP 层拦截，比 Mockito 打桩更真实——它能一并验证 URL 拼装、查询参数编码、JSON 反序列化和 HTTP 状态码处理。只有真正的外部边界（网络）被替换掉了。

---

## 9. 如何跑起来

### 9.1 环境要求

- JDK 21
- Maven（用仓库自带的 `./mvnw` 即可，无需本地安装）
- macOS（PDF 中文字体依赖系统字体；Linux/Windows 上中文会降级，英文 PDF 不受影响）

### 9.2 配置

编辑 `src/main/resources/application.yml`，填入你自己的 Gemini API Key：

```yaml
spring:
  ai:
    openai:
      api-key: <your-gemini-api-key>
```

可选：配置 Google Places Key 启用真实停车数据

```bash
export PARKING_GOOGLE_API_KEY=xxx
# 同时把 application.yml 的 parking.provider 改成 google
```

### 9.3 启动

```bash
./mvnw clean compile     # 编译
./mvnw test              # 跑测试（17 个用例，不需要网络）
./mvnw spring-boot:run   # 启动，监听 8123
```

### 9.4 验证

打开 **Knife4j 文档**：<http://localhost:8123/api/doc.html>

三个可以直接点开测的接口：

```bash
# 1. 落日评估（不依赖大模型，最快验证）
curl "http://localhost:8123/api/ai/weather/sunset-check?lang=zh"

# 2. 停车查询（纯本地，秒回）
curl "http://localhost:8123/api/ai/places/parking-check?lat=-33.8970&lng=151.1795&poiName=Newtown&lang=zh"

# 3. 主链路行程规划（会调 Gemini，需要 API Key）
curl "http://localhost:8123/api/ai/itinerary/plan?message=帮我安排Surry Hills咖啡半日游&chatId=test1&lang=zh"
```

### 9.5 观察缓存生效

连续调两次 `sunset-check`，日志里的「回源 Open-Meteo 查询气象数据」**只会出现一次**。

### 9.6 启动 MCP 子模块（可选）

```bash
cd yu-image-search-mcp-server
./mvnw clean package
# 然后取消 application.yml 里 spring.ai.mcp 的注释
```

---

## 10. 已知短板与技术债

**面试官问"这个项目有什么不足"时，主动说出来比被问出来强得多。** 以下每一条都配了改进方向。

### 10.1 安全

| 问题 | 影响 | 改进方向 |
|---|---|---|
| API Key 明文写在 `application.yml` 并提交进了 Git | Key 泄露 | 改为环境变量 `${GEMINI_API_KEY}`，历史提交用 `git filter-repo` 清理 |
| `TerminalOperationTool` 能执行任意 shell 命令 | 如果注册给模型，等于远程代码执行 | 目前未注册（安全），若启用需要命令白名单 + 沙箱 |
| CORS 配置 `allowedOriginPatterns("*")` + `allowCredentials(true)` | 生产环境过于宽松 | 生产 profile 收紧到具体域名 |

### 10.2 并发与可用性

| 问题 | 影响 | 改进方向 |
|---|---|---|
| **PDF 输出路径写死** `tmp/pdf/itinerary.pdf` | 并发导出时两个请求互相覆盖，可能把别人的行程发给用户 | 改用 `Files.createTempFile()` 或直接返回 `ByteArrayOutputStream` |
| `InMemoryChatMemoryRepository` | 重启丢失记忆，多实例不共享 | 换成 Redis 或启用已实现的 `FileBasedChatMemory` |
| Caffeine 是**单机**缓存 | 多实例部署时每个节点各缓存一份 | 数据量小、一致性要求低，可接受；要求高就上 Redis |
| 没有限流 / 熔断 | 大模型调用失败会持续重试 | 引入 Resilience4j 的 CircuitBreaker |

### 10.3 功能完整性

| 问题 | 说明 |
|---|---|
| 前端是模板遗留 | `yu-ai-agent-frontend` 还是 LoveMaster/SuperAgent 页面，没适配行程功能 |
| MCP 默认关闭 | 配置注释掉了，避免启动依赖外部进程 |
| POI 库只有 4 条 | 硬编码在代码里，应该外置到数据库或配置文件 |
| 停车库只有 9 条 | 同上 |
| 行程节点没有经纬度 | 导致 `attachParking` 只能传 `(0,0)` 靠店名匹配，精度有限 |
| `ItineraryRequest` 类未被使用 | Controller 改成了 `@RequestParam`，这个 DTO 成了孤儿 |
| PDF 中文字体依赖 macOS | 部署到 Linux 容器会降级成 Helvetica，中文字符被 `sanitize` 丢弃 |

### 10.4 可观测性

没有接入 Micrometer / Prometheus，也没统计大模型的 token 消耗和 P99 延迟。生产项目这是必需的。

---

## 11. 面试问答速查

### Q1：介绍一下你这个项目

> 这是一个基于 Spring AI 和 Gemini 的悉尼本地生活行程规划智能体。核心解决两个问题：一是大模型输出不可控——我用 Spring AI 的结构化输出把自然语言变成强类型 JSON，前端可以直接渲染；二是大模型会幻觉——我用私域 POI 库做 RAG 检索抑制编造，同时把停车、天气这类确定性数据在模型返回后由后端覆盖写入，模型只负责创造性的编排。
>
> 技术上有三个亮点：第一是天气落日决策引擎，实时调 Open-Meteo 算出今天值不值得看日落，然后**动态生成两套完全相反的提示词**注入；第二是外部调用的防御体系，Caffeine 缓存 + 2s/3s 超时 + 异常降级三层保护；第三是 iText 9 的双语 PDF 排版，处理了字体探测、字形校验、emoji 降级等一堆跨语言排版问题。

### Q2：`@Cacheable` 为什么要单独拆一个 Bean？

> 因为 `@Cacheable` 依赖 Spring AOP 代理。同一个类内部的 `this.method()` 自调用不会经过代理，缓存完全不生效——这是 Spring 缓存和事务最经典的坑。我把 HTTP 取数逻辑拆到 `OpenMeteoWeatherService`，`WeatherService` 通过依赖注入拿到代理对象，调用才会被 `CacheInterceptor` 拦截。
>
> 另外我特意缓存的是**语言无关的原始预报**而不是最终的 `SunsetEvaluation`，否则中文请求先落缓存，英文请求就会拿到中文文案。

### Q3：外部 API 抖动怎么容错？

> 四层防护。
>
> 第一层是**超时**：连接 2 秒、读取 3 秒。Spring MVC 是同步阻塞模型，一个请求占一个 Tomcat 线程，不设超时的话外部接口挂了会把 200 个线程全占满，整个应用雪崩。
>
> 第二层是**缓存**：Caffeine 按坐标+日期做 key，30 分钟过期。天气数据本来就不需要秒级实时，这一层能挡掉绝大部分重复请求。
>
> 第三层是**降级**：所有外部调用包在 try-catch 里，失败返回兜底对象。天气服务挂了行程照样能规划，只是少了落日推荐。
>
> 第四层是**失败不入缓存**：我把 try-catch 放在上层而不是缓存方法内部。`@Cacheable` 的规则是方法抛异常就不写缓存，这样故障不会被固化 30 分钟。这一点我专门写了单元测试 `testFailureIsNotCached` 来钉住。

### Q4：怎么抑制大模型幻觉？

> 三个手段，从软到硬。
>
> 最软的是 **RAG 上下文注入**：从私域 POI 库检索出真实存在的店铺，格式化后塞进 System Prompt，模型有了参考就不容易编造。
>
> 中间是**显式的反幻觉指令**：比如识别到自驾场景时，我会在提示词里明确写"不要编造具体停车场名称或评分，停车信息将由服务端按节点挂载"。
>
> 最硬的是**后端数据覆盖**：不管模型填了什么，`plan.setSunsetEvaluation()` 和 `attachParking()` 都会用真实数据覆盖掉。这是最可靠的一层——不依赖模型听不听话。
>
> 核心原则是：**确定性的事情交给代码，创造性的事情交给模型。**

### Q5：为什么放弃向量检索改成关键词匹配？

> 直接原因是 Gemini 的 OpenAI 兼容端点不支持 embedding 路由，启动就 404。我评估后决定彻底移除远程 Embedding 依赖，改成内存加权评分。
>
> 但退一步说，这个降级本身是合理的：当前只有 4 个 POI，向量检索是过度设计——建索引、算余弦相似度的开销远大于遍历 4 条记录。而且我的评分维度更可控：店名 8 分、标签/氛围/视觉风格各 5 分、类别 4 分，可以精确调权重。等数据量上千、需要语义泛化能力时再上向量库不迟。
>
> 保留的扩展性是：`PgVector` 依赖和配置都还在，切回去只要恢复配置和 `RagConfig`。

### Q6：Tool Calling 和硬编码前置调用怎么选？

> 我的划分标准是**这个调用是否必然发生**。
>
> 天气和停车我用**硬编码前置调用**：每次行程规划都必须有这两份数据，交给模型决定的话，它可能不调、可能调错参数、还要多消耗一轮 token 和一次往返延迟。前置调用可以并行、可以缓存、可以稳定降级。
>
> `WeatherTool` 和 `DistanceMatrixTool` 这类注册成 Tool 的，走的是**自由对话链路**（`/tools` 接口）——用户问什么不确定，让模型自己判断要不要查。
>
> 一句话：**主链路上确定要调的，用代码调；探索性链路上按需调的，用 Tool Calling。**

### Q7：Agent 的 ReAct 循环怎么实现的？

> 三层抽象。`BaseAgent` 管状态机（IDLE/RUNNING/FINISHED/ERROR）和执行循环，带 `maxSteps` 安全阀防止无限循环烧钱。`ReActAgent` 把 `step()` 拆成 `think()` 和 `act()`。`ToolCallAgent` 实现这两个方法。
>
> 最关键的一点是我**关掉了 Spring AI 内置的工具执行**（`internalToolExecutionEnabled(false)`），自己用 `ToolCallingManager` 执行。这样每一步都可观测——能打日志、能通过 SSE 实时推给前端、将来还能插入人工审核环节。内置机制是黑盒的，做不到这些。
>
> 终止靠 `TerminateTool`：一个什么也不做的空工具，唯一作用是给模型一个显式的"我完成了"信号。`act()` 里检测到 `doTerminate` 被调用就把状态置为 FINISHED。这比解析模型输出的文本猜测它是否完成要可靠得多。

### Q8：结构化输出的原理？可靠吗？

> `.entity(ItineraryPlan.class)` 背后是 `BeanOutputConverter`：用 `jsonschema-generator` 从 Java 类反射生成 JSON Schema，拼进提示词告诉模型输出格式，拿到文本后用 Jackson 反序列化。
>
> 所以**字段名和注释本身就是提示词的一部分**，命名质量直接影响输出质量。
>
> 可靠性上它不是 100% 的——模型仍可能输出不合法 JSON。我的兜底是：`ItineraryPlan` 和 `ItineraryNode` 的所有字段都允许为 null，渲染层（PDF）对每个字段都做了 `fallback(value, 默认文案)` 处理，我还专门写了 `testExportTolerateMissingFields` 测试全空字段不崩溃。真要更硬的保证，可以上模型原生的 JSON Mode 或 function calling schema 约束。

### Q9：为什么有的 DTO 用 record 有的用 class？

> 按**是否需要可变**来分。`ParkingInfo` 和 `SunsetEvaluation` 生成后不再修改，用 `record`——字段 final、自动生成 equals/hashCode、代码量少一半，而且天然线程安全。
>
> `ItineraryPlan` 和 `ItineraryNode` 必须可变，因为后端要在模型返回后 `setSunsetEvaluation()` 和 `setParkingInfo()` 覆盖写入，所以用 Lombok `@Data` 类。
>
> 这里还踩过一个坑：我一开始给 `sunsetEvaluation` 加了 `@JsonProperty(access = READ_ONLY)`，结果前端 POST 回来导出 PDF 时这个字段反序列化不出来，落日卡片渲染不了。后来去掉了——反正后端每次都会覆盖，不怕前端传脏数据。

### Q10：这个项目有什么不足？

参见 [第 10 章](#10-已知短板与技术债)。**至少要能说出 PDF 固定路径的并发覆盖问题和 API Key 明文提交的问题**，这两个最能体现你真的审视过自己的代码。

---

## 12. 附录：术语表

| 术语 | 全称 | 一句话解释 |
|---|---|---|
| LLM | Large Language Model | 大语言模型，给文本返回文本 |
| Prompt | - | 提示词，发给模型的输入 |
| System Prompt | - | 系统提示词，设定模型身份和规则，用户不可见 |
| Token | - | 模型处理文本的最小单位，按 token 计费 |
| Hallucination | - | 幻觉，模型一本正经地编造不存在的事实 |
| RAG | Retrieval-Augmented Generation | 检索增强生成，先查资料再让模型回答 |
| Embedding | - | 把文本转成数字向量，用于计算语义相似度 |
| Vector Store | - | 向量数据库，存 embedding 并支持相似度检索 |
| Tool Calling | Function Calling | 让模型能"申请"调用你写的函数 |
| MCP | Model Context Protocol | Anthropic 提出的工具接入标准协议 |
| Agent | - | 能自主循环"思考-行动"直到完成任务的智能体 |
| ReAct | Reasoning + Acting | Agent 的经典范式：推理和行动交替进行 |
| Advisor | - | Spring AI 的拦截器，可在请求前/响应后插入逻辑 |
| ChatClient | - | Spring AI 的高层 API，链式调用构建对话 |
| ChatMemory | - | 对话记忆，让模型记住上下文 |
| Structured Output | - | 结构化输出，让模型返回可解析的 JSON |
| BeanOutputConverter | - | Spring AI 实现结构化输出的组件 |
| SSE | Server-Sent Events | 服务端单向推送，用于流式输出 |
| Haversine | - | 球面距离公式，根据经纬度算两点实际距离 |
| Caffeine | - | 高性能 Java 本地缓存库 |
| iText | - | Java PDF 生成库 |
| TTC | TrueType Collection | 一个文件打包多个字重的字体格式 |
| FieldMask | - | Google API 里声明"我要哪些字段"的机制，按字段计费 |
| Graceful Degradation | - | 优雅降级，部分功能故障时主流程仍可用 |
| Knife4j | - | 增强版 Swagger UI，生成 API 文档 |

---

## 结语：三句话记住这个项目

1. **架构上**，它是一条"自然语言 → 强类型 JSON → 可打印文档"的管线，每一段都有明确的输入输出契约。
2. **设计上**，它的核心原则是**确定性的事情交给代码，创造性的事情交给模型**——天气、停车、语言由后端算准并覆盖，行程编排和文案由模型创造。
3. **工程上**，它在每个外部依赖点都做了防御——超时、缓存、降级、字体探测、字形校验，17 个离线单元测试把这些防御都钉住了。
