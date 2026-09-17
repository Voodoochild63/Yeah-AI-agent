# AI 行程规划智能体 · 面试梳理

> 一个基于 **Spring Boot 3.4 + Spring AI 1.0** 的城市漫游行程规划智能体：以 **Schema 约束的结构化输出** 替代不可控的自由文本，把**私域打卡库检索**与**实时气象落日决策**在提示词装配阶段融合进大模型上下文，一次对话产出可直接渲染的强类型时间轴，并支持**零 LLM 成本的画册级 PDF 导出**。
>
> 解决的痛点是：通用大模型排行程「看着像那么回事，但地点泛泛、不懂当日天气、结果没法直接落地到产品界面」。

| 项 | 值 |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.4.4 |
| Spring AI | 1.0.0 |
| LLM | Gemini 3.6-flash（OpenAI 兼容协议） |
| PDF | iText 9.1.0 |

---

## 1. 核心架构与技术栈

| 分层 | 核心组件 | 职责 |
| --- | --- | --- |
| 接入层 Controller | `AiController` / `WeatherController` / `HealthController` | 5 个业务端点：结构化规划、Tool Calling 对话、SSE 流式、PDF 导出、落日查询。Knife4j + springdoc 出文档 |
| AI 编排层 | `ItineraryApp`（ChatClient） | 组装 System Prompt（基础规则 + 私域候选池 + 落日规则）、挂 Advisor、发起结构化调用、回填元数据 |
| 会话记忆 | `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` | 按 chatId 隔离，滑动窗口 20 条。`FileBasedChatMemory`（Kryo 序列化）已实现但当前注释未启用 |
| 可观测 / 切面 | `MyLoggerAdvisor`（CallAdvisor + StreamAdvisor） | 同时覆盖同步与流式链路，打印入参 Prompt 与模型回复；`ReReadingAdvisor` 为可选推理增强 |
| 私域检索层 | `PoiRagService` | 内存 POI 库 + 多维加权打分（店名/类别/标签/氛围/视觉风格），取 Top-3 注入 Prompt；另供 PDF 反查私藏贴士 |
| 外部决策层 | `WeatherService`（RestClient → Open-Meteo） | 免鉴权拉取日落/云量/降水，本地规则引擎判定 + 三级兜底，产出强类型 `SunsetEvaluation` |
| Agent 层 | `BaseAgent` → `ReActAgent` → `ToolCallAgent` → `YuManus` | 自研 ReAct 循环：状态机 + 步数上限 + 手动 `ToolCallingManager`，`SseEmitter` 逐步回传 |
| 渲染导出层 | `ItineraryExportService` + `PDFGenerationTool`（iText 9.1.0） | 服务负责卡片版面，工具类持有设计系统：字重对解析、调色板、字形净化 |

---

## 2. 核心业务主链路

自然语言输入 → 结构化时间轴 → 落日决策注入 → PDF 导出的完整数据与控制流转。

### Step 01 · HTTP 接入

`GET /api/ai/itinerary/plan?message=…&chatId=…`

用户自然语言与 chatId 进入接入层。chatId 是多轮局部修正（"把第二站换成室内的"）能连贯的前提。

### Step 02 · 私域候选池检索

`poiRagService.searchPoi(message)`

对 query 做归一化后遍历内存 POI 库，命中店名 +8、标签/氛围/视觉风格各 +5、类别 +4、描述/贴士 +2，取 Top-3 拼成结构化文本。

### Step 03 · 外部落日决策

`weatherService.evaluateTodaySunset()`

同步调用 Open-Meteo 取当日日落时间、云量均值、最大降水概率；规则引擎判定；任何异常或字段缺失均降级为兜底对象而非抛错。

### Step 04 · 动态提示词装配

`buildSunsetRule(evaluation)`

按评估结果二选一注入互斥指令：达标走「日落机位激活」要求把落日点钉在时间轴末端；不达标走「天气不佳提示」禁止排户外落日、改写进 `backupSuggestions`。

### Step 05 · 强类型结构化输出

`.call().entity(ItineraryPlan.class)`

`BeanOutputConverter` 由 POJO 反射生成 JSON Schema 随 Prompt 下发，模型回包直接反序列化为对象，省掉手写 JSON 解析与容错。

### Step 06 · 服务端元数据回填

`plan.setSunsetEvaluation(evaluation)`

模型只负责行程本身；真实气象数据由服务端无条件覆盖写入根对象，杜绝模型编造天气数值。

### Step 07 · 按需 PDF 导出

`POST /api/ai/itinerary/export-pdf`

前端把当前页 JSON 原样 POST 回来，不重新调用大模型；导出链路零 LLM 成本，且所见即所得。

### Step 08 · 画册级排版渲染

`Content-Disposition: attachment`

iText 解析同族字重对、按码点净化文本，依次渲染标题、摘要卡、时间轴卡片、落日专题卡、备选清单，以文件流回写触发浏览器下载。

---

## 3. 简历闪光点与技术难点

### H1 · 实时气象信号 × 提示词工程的闭环

**问题**：大模型没有实时天气感知，直接问它"今天适合看日落吗"只会编造。而把原始 JSON 丢进 Prompt，模型又会自行解读阈值，结果不稳定。

- 判定权交给确定性规则引擎（降水 < 30%、云量 15%~75%），模型只负责把结论翻译成行程编排
- 两套互斥指令模板，分别约束 `schedule` 与 `backupSuggestions` 两个不同字段，让"不推荐"也有明确落点
- 三级降级：HTTP 异常 → 字段缺失 → 解析失败，全部收敛到同一个兜底对象，规划主链路永不中断

**Resume bullet (EN)**

> Built a weather-aware prompt orchestration layer that fuses a keyless Open-Meteo forecast feed with a deterministic rule engine (rain probability and cloud-cover thresholds), injecting mutually exclusive prompt directives so the LLM either pins a golden-hour stop to the end of the timeline or routes a graceful advisory into the fallback list; a three-tier degradation path keeps itinerary generation available when the upstream API fails.

### H2 · 强类型结构化输出与元数据主权分离

**问题**：LLM 返回自由文本时，前端要写一堆防御性解析；而如果让模型连气象数据一起输出，又会产生幻觉数值。

- Spring AI `BeanOutputConverter` 从 POJO 反射出 JSON Schema 随 Prompt 下发，回包直接落成对象
- 把 `ItineraryPlan` 从不可变 record 改为可变 POJO，使服务端可在模型返回后覆盖写入权威字段
- 踩坑修正：`@JsonProperty(READ_ONLY)` 既没能把字段挡在 victools 生成的 Schema 外，又会让导出接口丢字段，最终改为「允许进 Schema + 服务端无条件覆盖」

**Resume bullet (EN)**

> Enforced schema-constrained LLM output via Spring AI's BeanOutputConverter, eliminating hand-written JSON parsing, and separated data ownership so that model-authored itinerary content and server-authoritative weather metadata are merged server-side — preventing the model from hallucinating numeric forecast values while keeping the response a single typed object.

### H3 · 跨语种 PDF 排版的字体容错体系

**问题**：iText 内置中文字体依赖 test 作用域的 `font-asian`，运行期直接抛 `Type of font not recognized`；换系统字体后又踩到「字体能加载但取不到 CJK 字形」的隐性坑。

- 字体族枚举替代硬编码索引：遍历 ttc 内各字面，按名称特征（Semibold/Medium/W6）区分常规体与真实粗体
- 双重校验：候选字体必须同时通过中文与拉丁字符的 `containsGlyph` 探测，才允许入选
- 逐码点净化：emoji 在任何可嵌入中文字体中都无字形，按 ◆/•/▸ 降级链替换，最后才丢弃
- 只缓存字体规格字符串而非 `PdfFont` 实例——后者与 `PdfDocument` 绑定，跨文档复用会出错

**Resume bullet (EN)**

> Designed a resilient CJK/Latin PDF typography pipeline on iText 9: a font-family enumeration strategy that resolves a true weight pair (regular + semibold) from macOS TrueType collections instead of hard-coded indices, glyph-coverage probing that rejects fonts which load but cannot render CJK, and a per-codepoint sanitizer that degrades unsupported glyphs through a symbol fallback chain — replacing simulated bold and eliminating tofu boxes.

---

## 4. 模拟面试深水区问答

按 STAR 组织，每题附一条最可能的连环追问及应答口径。

### Q1 · 高并发下这个三方天气 API 抖动，你的系统会怎样？现在的兜底够吗？

**S 情境**：落日评估位于 `/plan` 主链路的同步路径上，每次规划都会打一次 Open-Meteo。当前已有 try-catch 兜底，但只解决了「不崩」。

**T 任务**：要在承认现有实现边界的前提下，给出可量化的容错与性能改造方案，而不是硬说自己已经做好了。

**A 行动**

1. 诚实定位现状：已有异常兜底与数据完整性校验，但确实缺三样东西——RestClient 未设连接/读超时、无缓存、无熔断
2. 超时优先：不设超时的同步外调是压测下最典型的线程池耗尽源头，先用 `ClientHttpRequestFactory` 设定连接 1s、读 2s
3. 缓存收敛 QPS：落日数据按「城市 + 日期」天然可缓存，Caffeine 设 TTL 30 分钟即可把 N 次请求压到每天个位数回源
4. 熔断与降级分层：Resilience4j 半开探测，熔断打开时直接返回 fallback 对象；由于下游是提示词片段而非强依赖，降级对用户几乎无感
5. 更彻底的方案是定时任务预热当日数据写入本地，主链路只读缓存，把外部 RT 完全移出请求路径

**R 结果**：结论要落在权衡上：当前实现保证了可用性（不崩、不阻断规划），但未保证性能与稳定性（无超时、无缓存）。这是有意识的取舍而非遗漏——先打通链路，再按压测数据补齐。

**预备连环追问**：追问「缓存击穿怎么办」时，答：key 维度是城市+日期，基数极小，用 Caffeine 的 `refreshAfterWrite` 做异步刷新即可，不需要上分布式锁。

### Q2 · 大模型幻觉在你这个场景具体表现是什么？私域上下文是怎么抑制的？

**S 情境**：行程场景的幻觉有两类：编造不存在的店铺，以及编造天气、营业时间这类可验证的事实性数值。

**T 任务**：需要说明两类幻觉用了完全不同的抑制手段，而不是笼统答一句"加了 RAG"。

**A 行动**

1. 地点幻觉——用私域候选池收敛生成空间：检索命中的 POI 以结构化文本注入 System Prompt，并明确要求「优先参考并选择这些地点」
2. 数值幻觉——用主权分离而非提示词约束：气象数据完全不信模型输出，服务端在模型返回后无条件覆盖写入
3. 刻意不编造：`ItineraryNode` 没有 `openingHours` 字段，PDF 该行渲染为「请参考当日营业状态」占位。评估过给 record 加字段让模型填，但真实营业时间是高风险幻觉点，宁可留白
4. 注意力增强预留：`ReReadingAdvisor`（Re2 重读）已实现，可在复杂指令下提升指令遵循度

**R 结果**：效果可验证：实测降水 46% 不达标那次，模型确实没有排户外落日节点，而是在 `backupSuggestions` 首条写明「已为您取消户外落日节点」——说明注入的互斥指令被正确遵循。

**预备连环追问**：如果面试官问「为什么不用向量检索」，必须如实答：Gemini 的 OpenAI 兼容端点不支持 embeddings 路由（404），所以降级为内存加权匹配；PgVector 与 SimpleVectorStore 的实现都在，数据量上来后可直接切回。

### Q3 · 落日这个逻辑，为什么不做成 Tool 让模型自己调，而是硬编码成前置规则？

**S 情境**：项目里两种范式同时存在：`ItineraryApp` 走前置注入，`YuManus` 智能体走 ReAct + Tool Calling，可以直接对比。

**T 任务**：要讲清选型依据是「确定性 vs 自主性」，并承认这是场景决定的，不是哪个技术更先进。

**A 行动**

1. 确定性优先：落日是每次规划都必须考虑的横切约束，而非条件性能力。做成 Tool 意味着把「要不要看天气」的决策权交给模型，可能漏调
2. 延迟与成本：Tool Calling 至少要两轮模型往返（决策调用 + 消费结果），前置注入只需一轮
3. 可测试性：规则引擎能用单测覆盖全部边界（我实测了 30% 与 15%/75% 三个临界点），而模型是否调用工具无法稳定断言
4. 反过来讲 Tool 的适用面：`YuManus` 里的搜索、文件、终端操作属于开放式任务，调用时机依赖推理，才值得交给模型自主编排

**R 结果**：一句话收尾：横切的、每次都要的、可用规则表达的约束走前置注入；条件性的、依赖推理判断时机的能力走 Tool Calling。

**预备连环追问**：若追问「那 `WeatherTool` 为什么还在」，如实答：那是 Tool Calling 演示链路上的 mock 实现（固定返回阵雨），与真实的 `WeatherService` 并存，生产化时应合并为同一数据源。

---

## 5. 面试前必须处理的风险项

> **这些是代码里的真实状态，不修补会在追问中暴露。** 前两项建议动手改掉，后四项建议准备好口径、由你主动讲出来 —— 主动暴露边界并给出改进方案，通常比被追问出来得分更高。

| 风险项 | 面试中的后果 | 处理方式 |
| --- | --- | --- |
| `application.yml` 中硬编码 Gemini API Key 并已入库 | 面试官扫一眼配置就会发现，直接影响安全意识评分 | 立即改为环境变量注入并轮换密钥，历史提交需清理 |
| PDF 导出固定文件名 `tmp/pdf/itinerary.pdf` | 并发导出会互相覆盖，可能下载到他人行程 | 改用 UUID 临时文件或直接写 `HttpServletResponse` 输出流 |
| 把 `PoiRagService` 说成「向量 RAG」 | 实现是内存关键词加权打分，追问检索召回率即穿帮 | 如实表述为「轻量语义标签检索」，并说明降级原因与回切路径 |
| Open-Meteo 调用无超时、无缓存、无熔断 | 高并发问题的必问点，也是主链路同步外调的隐患 | 补 RestClient 超时 + Caffeine 缓存，作为「下一步优化」主动讲出来 |
| `src/test` 目录不存在，测试代码在 `test_backup` | 工程规范项，容易被问到测试覆盖策略 | 至少恢复 `WeatherService` 规则引擎与 PDF 渲染的单测 |
| `WeatherTool` / `DistanceMatrixTool` 为 mock 实现 | 被追问 Tool Calling 效果时需要能立刻说清边界 | 主动声明是演示桩，真实数据走 `WeatherService` |

---

## 6. 可延伸的加分话题

### 自研 ReAct 智能体

`BaseAgent` 状态机（IDLE/RUNNING/FINISHED/ERROR）+ 步数上限防死循环，`ToolCallAgent` 关闭 Spring AI 内置工具执行、改用 `ToolCallingManager` 自行维护消息上下文，便于在每一步切入 SSE 推送与终止判定。

### SSE 流式与线程模型

`SseEmitter` 设 5 分钟超时，`CompletableFuture` 异步执行避免阻塞 Tomcat 工作线程，并注册 `onTimeout` / `onCompletion` 回调统一收敛资源清理。

### Advisor 切面设计

`MyLoggerAdvisor` 同时实现 `CallAdvisor` 与 `StreamAdvisor`，流式链路借助 `ChatClientMessageAggregator` 聚合分片后再记录，是 AOP 思想在大模型调用链上的直接映射。

### 踩坑即谈资

`RestClient` 的 `uri(String)` 会把字符串当 URI 模板二次编码，导致 `timezone=Australia%2FSydney` 变成 `%252F` 触发 400；改用 `UriBuilder` 逐参数传值解决。这类细节最能体现真实调试经历。
