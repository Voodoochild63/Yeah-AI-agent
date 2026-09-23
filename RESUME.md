# Yeah AI Agent · Resume Copy

面向求职投递的可粘贴语段。口径按当前代码真实能力来写：私域检索是内存加权匹配（非向量库），停车默认本地适配器并可切换 Google Places，天气走 Open-Meteo 规则引擎。面试时宁可讲清取舍，不要把降级方案说成向量 RAG。

---

## 项目标题（简历一行）

**Yeah AI Agent** — Sydney lifestyle itinerary assistant (Spring Boot / Spring AI)

---

## 一句话简介（Summary 栏）

Built a Spring Boot 3 / Spring AI itinerary agent that turns natural-language travel intent into a typed day plan, grounding the LLM with private POI retrieval, live sunset rules, and parking hints, then exporting a bilingual, zero-LLM-cost PDF.

用中文简历可改写为：

基于 Spring Boot 3 与 Spring AI 实现悉尼城市行程智能体：将自然语言偏好转为强类型一日行程，融合私域打卡检索、实时落日决策与停车建议，并支持中英双语、不重复调用大模型的 PDF 导出。

---

## 英文简历 Bullets（推荐直接粘贴）

面试官更认「约束模型 + 服务端权威数据 + 可降级」而不是「接了个 ChatGPT」。下面四条按这个标准写，每条都有可追问的设计点。

- Designed a Spring AI orchestration pipeline that injects private POI candidates and a deterministic sunset decision into the system prompt, then deserializes the model response into a typed `ItineraryPlan` via schema-constrained structured output (`BeanOutputConverter`) instead of brittle free-text parsing.
- Separated data ownership: the LLM authors the timeline, while weather metadata and parking recommendations are computed server-side and attached after generation, preventing hallucinated forecast numbers and keeping `/plan` available when Open-Meteo or Places is down.
- Implemented a zero-cost parking adapter over real Sydney garages (Wilson / Secure) with haversine walking estimates, plus a Google Places API (New) path behind `parking.provider`, falling back to the local adapter when the API key is missing or the call fails.
- Built an on-demand iText 9 export that never re-calls the LLM: bilingual (zh/en) layout, CJK/Latin font fallback, parking and transit cards with explicit contrast colors, and `keepWithNext` pagination so section titles are not orphaned across pages.

可选第五条（有智能体经历时再加，避免简历全是同一套 Agent 话术）：

- Implemented a ReAct tool-calling agent (`BaseAgent` → `ToolCallAgent`) with a step cap, manual `ToolCallingManager`, and SSE streaming, used for open-ended tool use while keeping the itinerary path on a cheaper single-round structured call.

---

## 中文简历 Bullets（推荐直接粘贴）

- 基于 Spring Boot 3.4 + Spring AI 1.0 搭建行程编排链路：私域 POI 检索与落日规则在 Prompt 装配阶段注入，使用 JSON Schema 约束的结构化输出将模型结果直接反序列化为 `ItineraryPlan`，避免手写 JSON 解析。
- 划分数据主权：行程时间轴由模型生成，气象评估与停车建议由服务端计算后回填；Open-Meteo / Places 异常时降级兜底，规划主链路不中断，同时抑制天气数值幻觉。
- 实现零成本停车适配器（悉尼核心商圈真实车库 + 步行距离估算），并以配置开关预留 Google Places API (New)；未配置 Key 或调用失败自动回退本地数据。
- 按需 PDF 导出（iText 9）不重复调用大模型：中英双语排版、PingFang / Helvetica 字重分离、停车与通勤卡片强制深色对比度，并用 `keepWithNext` 避免小节标题孤页。

---

## 技术栈（Skills 旁注，一行即可）

Java 21 · Spring Boot 3.4 · Spring AI · Gemini (OpenAI-compatible) · RestClient · Open-Meteo · iText 9 · Knife4j / springdoc · SSE

不要把「PgVector / Embedding」写进已上线能力；代码里相关配置是注释掉的降级路径，被追问时可以讲「Gemini 兼容端点不支持 embedding，因此改为标签加权检索，向量方案可回切」。

---

## 30 秒口述（面试开场）

I built a city itinerary agent, not a chatbot wrapper. The product problem is that a raw LLM plan looks fluent but invents venues and ignores today’s weather. I retrieve a private POI pool, run a sunset rule engine on Open-Meteo, and inject mutually exclusive prompt rules so the model either pins a golden-hour stop or writes a backup note. Structured output gives the frontend a typed timeline; parking and weather are attached by the server. PDF export is a separate on-demand API so we don’t pay for a second generation. Default parking is local and free; Google Places is behind a config flag with fallback.

---

## 面试官会追问的三句话（提前背熟）

1. **为什么停车 / 落日不做成 Tool Calling？**  
   横切、每次规划都需要、可用规则表达 → 前置注入，一轮模型调用。开放式任务（搜索、文件）才走 ReAct。

2. **幻觉怎么压？**  
   地点：私域候选池收敛生成空间。数值：服务端覆盖写入，不信模型填的云量和降水。营业时间故意不让模型编。

3. **为什么不是向量 RAG？**  
   Gemini OpenAI 兼容端点没有 embedding 路由（404）。当前是内存多维加权匹配（店名 / 标签 / 氛围 / 视觉风格）。数据量上来后可以切回 PgVector，接口形态已经按检索服务拆开。

---

## 不建议写上简历的表述

| 避免 | 原因 |
| --- | --- |
| “基于向量数据库的 RAG” | 实现是内存关键词加权，追问召回率会穿帮 |
| “接入 Google 停车 API 生产可用” | 默认 `parking.provider=mock`，Google 是预留实现 |
| “高并发、熔断、分布式缓存” | Open-Meteo 调用目前是同步 + try/catch，没有超时/缓存，被问细节会被动 |
| 把模板项目名 yu-ai-agent / 鱼皮 Manus 当自己的品牌 | 包名已迁到 `com.yeah.yeahaiagent`，口述统一用 Yeah AI Agent |

如需把「下一步」写成加分项，用这一句即可：*Next: Caffeine cache keyed by city+date and RestClient timeouts on the weather path.* 这比假装已经做完更像一线工程师。
