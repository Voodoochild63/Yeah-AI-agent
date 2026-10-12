# Yeah AI Agent · 简历项目描述

面向投递的可粘贴文案。口径对齐当前代码：LLM 只写行程文案与排序；地点、距离、通勤、营业时间、落日与停车由 Java 后处理覆盖，避免把降级方案说成向量 RAG 或「已接 Google 停车 API」。

---

## 项目标题（简历一行）

**Yeah AI Agent**｜悉尼生活方式行程智能体（Spring Boot 3 / Spring AI / Vue 3）

---

## 一句话简介（Summary / 项目概述）

基于 Spring Boot 3 与 Spring AI 实现的悉尼一日游规划智能体：把自然语言偏好转成强类型时间轴，用真实 Google Maps 高分店约束生成空间，再由服务端补齐通勤、营业时间防撞与落日决策，并支持中英双语界面与零二次计费的 PDF 导出。

English: Built a Spring Boot 3 / Spring AI itinerary agent that turns natural-language taste into a typed Sydney day plan, grounds the model on real Google Maps venues, then lets Java own commute, opening-hours collision, and sunset rules, with a bilingual Vue 3 UI and zero-LLM-cost PDF export.

---

## 中文简历 Bullets（推荐直接粘贴 4～5 条）

- 基于 **Java 21 + Spring Boot 3.4 + Spring AI 1.0** 搭建 Retrieve-Read-Filter-Reason 编排链路：先让模型生成英文检索词，再经 Serper Maps 抓取真实店铺并做 **评分 ≥ 4.0 / 评价 ≥ 20** 硬过滤，最后以 JSON Schema 结构化输出反序列化为 `ItineraryPlan`，避免手写解析与虚构店名。
- **划分数据主权**：时间轴与氛围文案由 LLM 生成；经纬度、Haversine 路网距离、步行/驾车/公交三种通勤预估、营业时间防撞、停车 Maps 深链与 Open-Meteo 落日评估均由服务端后处理回填，抑制天气数值与车库名幻觉。
- 实现行程时空后处理：用真实坐标计算相邻站距离（路网系数 1.25），动态换算三种出行时长；按到达时刻比对当日营业时间，标记 `OPEN / CLOSING_SOON / CLOSED`，闭店节点可一键局部替换而不推翻整条路线。
- 前端 **Vue 3 + TypeScript + Tailwind** 承接强类型时间轴：多模式通勤胶囊、营业状态胶囊、中英切换、单点 Shuffle 与请求取消；停车只生成 `parking near {店名} {街区}` 的 Google Maps 检索链，不编造商业车库实体。
- 按需 **iText 9 PDF 导出**不重复调用大模型：中英双语排版与 CJK/Latin 字体回退；气象路径用 Caffeine 缓存（30 分钟）降低 Open-Meteo 重复请求。旁路保留 ReAct Tool Calling 智能体，与主规划的单轮结构化调用隔离。

---

## 英文简历 Bullets（投英文岗可替换）

- Designed a Retrieve-Read-Filter-Reason Spring AI pipeline: LLM writes English Maps queries, Serper `/maps` returns real venues filtered at rating ≥ 4.0 and ≥ 20 reviews, then schema-constrained structured output deserializes into a typed `ItineraryPlan` instead of brittle free-text parsing.
- Separated data ownership: the model authors the bilingual timeline, while Java post-processing attaches Haversine + road-factor commute estimates (walk / drive / transit), opening-hours collision (`OPEN` / `CLOSING_SOON` / `CLOSED`), Maps parking deep links, and an Open-Meteo sunset rule engine with Caffeine caching.
- Built a Vue 3 itinerary UI that binds typed transit capsules and hours status, supports zh/en, per-stop shuffle without rewriting the rest of the route, and never invents garage entities—parking is a `parking near {venue} {suburb}` Google Maps search intent.
- Shipped on-demand iText 9 PDF export with no second LLM call (CJK/Latin font fallback, bilingual layout). Kept a ReAct tool-calling agent on a side path so open-ended tool use does not inflate the cost of the main `/plan` flow.

---

## 技术栈（Skills 一行）

Java 21 · Spring Boot 3.4 · Spring AI · Gemini（OpenAI 兼容） · Vue 3 / Vite / TypeScript / Tailwind · Serper Maps · Open-Meteo · Caffeine · iText 9 · RestClient

---

## 30 秒口述（面试开场）

我做的不是 ChatGPT 套壳，而是一个「模型写体验、Java 写事实」的行程产品。通用大模型排一日游看起来流畅，但会编店名、忽略当天天气和打烊时间。我先用检索词 + Serper 把候选收敛到真实高分店，再用结构化输出拿到时间轴；距离、通勤、营业时间、落日和停车全部服务端计算后挂上去。前端是 Vue 3 强类型时间轴，导出 PDF 不再打一次模型。私域打卡目前是内存多维加权匹配，不是向量库——兼容端点没有 embedding 路由，这个取舍我可以讲清楚。

---

## 面试官可能追问（提前对齐口径）

1. **为什么通勤 / 营业时间 / 落日不做 Tool Calling？**  
   每次规划都需要、可用规则表达 → 放在后处理，主链路一轮结构化调用。开放式任务才走 ReAct。

2. **幻觉怎么压？**  
   地点：Serper 高分池 + 禁止凭空捏造。数值：天气与距离由服务端覆盖。停车：只给 Maps 检索词，不编车库名。营业时间：走 Maps `openingHours`，解析失败时公园类降级为全天开放，而不是让模型编时刻。

3. **为什么不是向量 RAG？**  
   Gemini OpenAI 兼容端点没有 embedding。当前是标签 / 氛围 / 视觉风格的内存加权检索，接口已按检索服务拆开，数据量上来可切 PgVector。

---

## 不建议写上简历的表述

| 避免 | 原因 |
| --- | --- |
| 「基于向量数据库 / Embedding 的 RAG」 | 实现是内存关键词加权，追问召回率会穿帮 |
| 「接入 Google Places 停车 API / 真实车库库存」 | 停车是 Maps 检索意图，不是车库实体或官方停车 API |
| 「高并发、熔断、分布式」 | 规划主链路仍是同步 Servlet + 串行外部调用 |
| 沿用模板名 yu-ai-agent / 鱼皮 Manus | 对外统一用 **Yeah AI Agent** |

投国内岗用中文 4～5 条即可，不必中英叠放。英文岗用英文四条。
