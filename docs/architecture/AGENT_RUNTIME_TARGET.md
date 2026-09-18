# Agent Runtime 目标架构（WeChatBot 重构方向 · 拷问修正版 v2.1）

> 本文档为 v1 经 **grill-me skill**（来自 GitHub `mattpocock/skills`，已装进 `~/.workbuddy/skills/grill-me` + `grilling`）逐题拷问后的修正版。v1 里"fashion 子图塞 5 个 agent"被用户一问推翻——**穿搭推荐本质是检索+排序，不该用 multi-agent**。v2 的 agent 边界收敛到最小。
>
> **v2.1（2026-08-30）**：Q3 由"手搓图运行时"改为"**采用 `spring-ai-alibaba-graph` 作单脊梁**"，并新增 §10 单脊梁 cutover 方案——彻底收编旧 System A（`AgentCoordinator`）+ System B（`SpringAiChatCompletionsGateway`）+ 已建 9 个手搓骨架，**杜绝三轨并存的四不像**。

## 0. 拷问方法论与共识来源

- 用 `grill-me` / `grilling` 沿**决策树**逐题追问，每题给推荐答案、一次一题，直到 frontier 为空。
- 10 个最上游分支全部落定（见 §6 决策树终态）。落地前不写任何代码，先在此达成共识。

## 1. 十项已敲定决策（决策树终态）

| # | 分支 | 结论 |
|---|---|---|
| Q1 | 设计方式 | **从 0 开始设计**（不是 strangler-fig 渐进，也不是为凑考点硬加抽象） |
| Q2 | 范围边界 | agent 内核从零设计；基础设施（RAG 语料/MCP Server/MySQL/Redis/Docker/Langfuse）**原地保留复用** |
| Q3 | 技术栈 | **Java / Spring AI + 采用 `spring-ai-alibaba-graph` 作图脊**（LangGraph 启发的 Java 生产级 agent 框架，阿里 JManus 已生产验证；避免手搓图运行时的低含金量与 A/B 双轨并存的四不像） |
| Q4 | 中枢抽象 | **图运行时当 spine**（`AgentState`+`Node`+`Edge`+`AgentGraph`+`Checkpoint`）；复杂节点（Planner/Stylist）**内部包 agentic 工具循环** |
| Q5 | 顶层拓扑 | 根 = **Planner**（轻量 LLM，做路由+深度决策）；子图 = `fashion`（深）+ `wardrobe`/`reminder`（轻） |
| Q6 | agent 边界 | fashion 子图**只留 Stylist + Critic 两个 LLM 节点**；analyze/rag/rerank/responder 全为确定性/结构化输出节点（**推翻 v1 的 5-agent**） |
| Q7 | 记忆 | **episodic + semantic 两层**（落 Redis）；procedural 留作文档里的"未来扩展"，不硬实现 |
| Q8 | 护栏 | **完整护栏**：max-steps 限环 + token budget + 模型分级（router 小模型 / worker 强模型）+ 节点级降级兜底 |
| Q9 | 持久化/人在环 | **Checkpoint**（图状态落 Redis，多轮/断点恢复）+ **轻量 HITL**（用户修正偏好即时写入 semantic memory）；不做完整审批流 |
| Q10 | 评测/可观测 | **Langfuse 全链路 trace + P5 离线回归 + trajectory 级评估**全保留强化（这是"不过度设计"的客观证据） |

## 2. 目标架构总览（修正后）

```
微信 iLink 通道
   ↓
Planner 节点（轻量 LLM：意图分类 + 路由 + 深度决策）
   ↓ （图 spine：spring-ai-alibaba-graph — OverAllState + NodeAction + StateGraph + CompiledGraph + Checkpoint）
统一 AgentGraph
   ├─ 记忆层（episodic 多轮 + semantic 用户画像/禁忌，落 Redis；fashion 子图 step0 读入 OverAllState）
   ├─ fashion 子图（深）  retrieve_memory(确定) → planner(LLM,含规则回退) → rag(确定) → stylist(LLM) → critic(LLM,可打回) → responder(LLM)
   │       └─ Critic 打回时走回边 loop 回 stylist（仅小回环，非全图循环）
   ├─ wardrobe 子图（轻） 摄入/查询衣橱，确定性节点为主
   ├─ reminder 子图（轻） 定时/规则提醒
   ├─ 工具（全经 MCP）     抠图/天气/联网/试穿/检索，与运行时解耦
   └─ Guardrails           max-steps / token / 模型分级 / 节点级降级
   ↓
LLM 分级：router 小模型 + worker 强模型
   ↓
Langfuse/OTel：全链路 trace + P5 回归 + trajectory 评估
```

关键变化（相对 v1）：
- **砍掉伪 agent**：Trend/Coordinator/Analyzer 不是独立 agent——趋势=检索上下文，协调=图的边，分析=一次结构化输出。
- **图里绝大多数是确定性节点**，只有 Stylist/Critic 是 LLM 驱动。这比"5-agent 流水线"更真实、更能讲清"我只在必要处用 agent"。
- `fashion_consultant` 从"外部工具"变为"fashion 子图入口"，消除双运行时与 38 条正则+反射兜底。

## 3. 运行时核心抽象 → 面试考点映射（修正）

| 抽象 | 面试考点 | 说明 |
|---|---|---|
| `AgentGraph`/`AgentState`/`Node`/`Edge` | 怎么设计 agent / 编排 | 显式图，告别 if/else 与正则编排 |
| `Planner` | 规划与推理 / Routing | 根节点做意图分类与深度决策；失败时回退关键词规则（见 §5 假设①） |
| `MemoryStore`（episodic/semantic） | 记忆与上下文 | 多轮对话 + 用户画像/禁忌；procedural 留扩展 |
| `ToolAdapter` + MCP | 工具调用 | 工具统一经 MCP，运行时与工具解耦 |
| `Guardrails` | 成本/延时/死循环 | max-steps、token budget、模型分级、节点降级 |
| `Reflexion`（Critic 内） | 自我批判/纠错 | 仅 Critic 打回 Stylist 时的小回环，非独立图循环 |
| `Checkpoint` / HITL | 持久化/人在环 | 多轮可恢复 + 偏好修正即时写记忆 |

## 4. 多 agent 协作模式（仅在有真实增益处命名）

- **Routing**：Planner 意图分流。
- **Evaluator-Optimizer**：Critic 评审 Stylist（可打回重生成）。
- 其余（Parallelization/Orchestrator-Workers）**不在 v2 主链路使用**——原 v1 的 Critic∥Trend、Coordinator 裁决经拷问判定为过度设计，已从主链路删除（可作文档"可选深度模式"提及）。

## 5. 两个未单独立题、但已锁定的假设（请否决权）

1. **Planner 的回退**：Planner 用 LLM，但保留一层**关键词规则兜底**（LLM 失败/超时→走规则路由），属 Q8 节点级降级的一部分。
2. **工具全经 MCP**：所有外部能力（抠图/天气/联网/试穿/检索）继续走现有 Python MCP Server，运行时只依赖 `ToolAdapter` 抽象，不直连实现。

如任一条你不同意，告诉我，重新拷问该分支。

## 6. 分阶段落地（适配"从零设计"现实）

> 原则：先骨架可编译，再逐节点替换，每步用 P5 benchmark 锁回归。

- **P0 单脊梁 cutover（采用 spring-ai-alibaba-graph，删手搓 9 骨架）**
  - 引 `spring-ai-alibaba-graph` 依赖（`StateGraph`/`OverAllState`/`NodeAction`/`CompiledGraph` + checkpoint/HITL/上下文工程）。
  - 删除已建 9 个手搓骨架文件（`runtime/` 目录），职责由框架原语接管（映射见 §8 / §10）。
  - 收编 System A（`AgentCoordinator`）→ fashion 子图节点；收编 System B（`SpringAiChatCompletionsGateway`）→ MCP 工具节点 + Planner 规则回退（详见 §10）。
  - 入口收敛为唯一：`FashionAgentService → v2 graph`（CompiledGraph）。
- **P1 内层结构化输出**：`AgentLlmCaller` 弃手搓 JSON，改 Spring AI 结构化输出（`.entity(Class)` / JSON-schema）。✅ 已完成（2026-08-31）：改用 `BeanOutputConverter`（复用宽松 ObjectMapper），保留超时/重试/thinking-disabled/JSON-Mode 全部能力 + 宽松兜底；AgentLlmCallerTest 4/4、图测试 4/4 绿，真实 P5 回归 37.29% 无退化。
- **P1-4 量化数字**：✅ 已完成（2026-08-31，`docs/quantitative-metrics.md`）：简单=2 次 LLM、复杂=5 次（critic∥trend 并行，墙钟≈4 次），时延 p50≈1s/p95≈6.4s，P5 37.29% 口径全部落文档。
- **P2 Planner 换正则兜底**：轻量 LLM Planner 节点 + 关键词规则回退，替换网关 38 条正则 + `maybeAutoX` 反射。→ **重新评估（2026-08-31）**：System B 实为微信对话主控制层（41 工具 + 意图裁剪 + 产品兜底），非图节点出口，删 gateway 的迁移面被低估，改为保留；P2 的"Planner 规则回退"由图内 `planner` 节点 `AnalyzedQuery.fallback` 承担（已有）。
- **P0-2 自主工具循环**：✅ 已完成（2026-08-31）：图内新增 `tool_loop` 节点（rag→tool_loop→stylist），`app.fashion.graph.tool-loop.enabled` 门控（默认 false），命中「城市+天气」规则才触发 ChatClient 工具循环（复用 `get_current_weather`），未命中零 LLM 开销；真实验证通过（ToolLoopLiveTest：模型自主调天气 → TOOL_CONTEXT → stylist 使用，图不降级）。
- **P3 fashion 子图化**：接入 `AgentGraph`，节点 = analyze→rag→rerank→Stylist(LLM)→Critic(LLM)→responder；wardrobe/reminder 轻量子图。每加/改一个节点跑 P5，无增益不保留。
- **P4 记忆/护栏/Checkpoint/可观测**：episodic+semantic 落 Redis；Guardrails 全量；Checkpoint 多轮；Langfuse 全链路 + trajectory 评估接上。

## 7. 面试叙事（统一双项目）

- 「我主导了一个生产级微信穿搭 agent：**图运行时当 spine**，绝大多数是确定性节点，只在 Stylist/Critic 两处用 LLM；配 Planner 路由、两层记忆、完整 Guardrails、Checkpoint 多轮与 Langfuse 轨迹评测。我用 P5 离线指标锁每次改动不退化。」
- 「我刻意没上 multi-agent swarm——穿搭推荐本质是检索+排序，多 agent 只会加延迟和不确定性。我只在需要真推理（搭配生成、约束校验）和处理失败（Critic 打回重生成）处用 agent。」
- 「另一项目用 LangGraph 做深度 agentic 研究系统；两套共享同一套 agent 设计语言（State/Node/Edge/Checkpoint/Planner/Memory）——一个跑在 Java 生产框架（spring-ai-alibaba-graph，LangGraph 启发）、一个跑在 Python 研究框架（LangGraph），跨生态框架掌控力，而非"手搓 vs 框架"。」

## 8. 现有 9 个骨架文件 → v2 映射

| 手搓骨架文件 | v2.1 处置 | 由框架原语接管 |
|---|---|---|
| `AgentState.java` | 删除 | `OverAllState`（含 KeyStrategy） |
| `Node.java` | 删除 | `NodeAction` / `AsyncNodeAction` |
| `AgentGraph.java` | 删除 | `StateGraph` → `CompiledGraph` |
| `Plan.java` | 删除 | Planner 输出的 state 字段（结构化输出） |
| `Planner.java` | 删除 | `NodeAction`（或框架 planning） |
| `MemoryStore.java` | 删除 | Redis-backed 记忆（spring-ai-alibaba Memory / 自管 Redis） |
| `ToolAdapter.java` | 删除 | MCP 工具注册（`@Tool` / 框架 MCP） |
| `Guardrails.java` | 删除 | 框架上下文工程策略 + 自定义 max-steps/token/分级 |
| `Reflexion.java` | 删除 | Critic 节点的回边（loop back to stylist） |

## 9. 竞争力评估与补强清单（2026-08-30 评估）

> 结论：架构设计已**完整**，足以让项目"像真实 agent 实习项目"；但要"有竞争力（简历堆里被捞出来）"还差 P0 三项。

### 9.1 已立住的竞争力支柱
- **克制地使用 agent**：minimal-agent 立场（拒绝 multi-agent swarm）是 2026 面试最吃的一套叙事。
- **基础设施资产全保留**：RAG / MCP / Docker / Langfuse → "已上线、可观测、有部署"。
- **评测纪律**：P5 + trajectory eval → "用量化数据证明设计，不靠感觉"。
- **双项目框架叙事**：生产 Java 框架 agent（spring-ai-alibaba-graph，LangGraph 启发）vs 研究型 Python LangGraph agent——跨生态框架掌控力，而非"手搓 vs 框架"。

### 9.2 缺口（按竞争力优先级）
**P0 — 不补会露怯（已提为落地必做）**
1. **cutover 策略（详见 §10）**：采用 spring-ai-alibaba-graph 作单脊梁后，旧 System A（`AgentCoordinator`）、System B（`SpringAiChatCompletionsGateway`）、已建 9 个手搓骨架**三者全部退役**，入口收敛为唯一 `FashionAgentService → CompiledGraph`，杜绝三轨并存的四不像。
2. **自主工具循环**：当前工具（抠图/天气/试穿）更像固定流水线步骤，需让 Stylist 或独立 tool 节点真正跑 ReAct 循环，补强"agent 自主用工具"考点。
3. **trajectory eval 口径**：明确"什么叫走对"（子图/节点是否正确），并保证 redesign 后 P5 不退化、minimal-agent 不亏。

**P1 — 补了更亮眼**
4. 量化数字：简单请求=2 次 LLM call/<3s、深度=4 次/~Xs、token 成本对比。
5. 测试：图运行时 + Guardrails 单元测试（max-steps 触发、节点降级）。
6. Planner 深度：加 plan-and-execute（复杂请求拆工具计划）。

**P2 — 锦上添花**
7. 对外 README / 架构图讲故事；8. streaming 体验。

### 9.3 关键风险
- **DeepResearch 仅 spec 未开工**（`docs/deepresearch-requirements.md`）：v2 叙事的"双项目统一设计语言"有一半依赖它。要么尽快启动，要么暂不把"双项目"当主卖点。

---

## 10. 单脊梁 Cutover 方案（整合 CUTOVER_PLAN.md，唯一改动清单）

> 本 § 已吸收原 `CUTOVER_PLAN.md` 全部内容；后者已降为仅含本段链接的 stub，避免双源漂移。状态：方案待确认，**落地前不动业务代码**。

### 10.1 为什么要改（现状问题）

当前仓库存在**三套并行编排路径**，这正是"四不像"的根因：

| 路径 | 位置 | 角色 |
|---|---|---|
| System A | `fashion/ai/fashion/look/agent/AgentCoordinator.java` | 手写 5 步 DAG（processInternal） |
| System B | `fashion/ai/service/SpringAiChatCompletionsGateway.java` | ReAct 工具循环 + 38 条正则 + `maybeAutoX` 反射兜底 |
| 手搓骨架 | `fashion/agent/runtime/` 下 9 个文件 | 自研 `AgentState`/`Node`/`AgentGraph`…（与框架重复） |

风险：面试观感像"没想清楚的历史包袱"；三套都要维护，改动易互相牵制。

### 10.2 目标态

- **唯一图脊**：`spring-ai-alibaba-graph`（LangGraph 启发的 Java 生产级框架，阿里 JManus 已验证）——`StateGraph` / `OverAllState` / `NodeAction` / `CompiledGraph` + checkpoint / HITL / 上下文工程。
- **唯一入口**：`FashionAgentService`（`@Tool(name="fashion_consultant")`）→ 构建并持有 `CompiledGraph`。微信消息不再进旧网关。
- **节点构成**：绝大多数是确定性节点，仅 `stylist` / `critic` 两处 LLM（minimal-agent 立场不变，见 §1 Q6）。

### 10.3 改动清单（按「删 / 退 / 加」三类）

#### A. 删除（手搓 9 骨架，被框架原语整体取代）

| 文件（runtime/） | 由框架原语接管 |
|---|---|
| `AgentState.java` | `OverAllState`（含 KeyStrategy） |
| `Node.java` | `NodeAction` / `AsyncNodeAction` |
| `AgentGraph.java` | `StateGraph` → `CompiledGraph` |
| `Plan.java` | Planner 输出的 state 字段（结构化输出） |
| `Planner.java` | `NodeAction`（或框架 planning） |
| `MemoryStore.java` | Redis-backed 记忆（spring-ai-alibaba Memory / 自管 Redis） |
| `ToolAdapter.java` | MCP 工具注册（`@Tool` / 框架 MCP） |
| `Guardrails.java` | 框架上下文工程策略 + 自定义 max-steps/token/分级 |
| `Reflexion.java` | Critic 节点的回边（loop back to stylist） |

> 目录 `runtime/` 整体清空。这是"不手搓图运行时"原则的落地，也是消除四不像的关键。

#### B. 退役（先标 `@Deprecated`，P5 回归无退化后物理删除）

| 文件 | 收编去向 |
|---|---|
| `AgentCoordinator.java` | fashion 子图节点（原 5 步映射见下表） |
| `SpringAiChatCompletionsGateway.java` | MCP 工具节点 + `planner` 规则回退 |

**System A 5 步 → 图节点映射：**

| 原步骤 | 新节点 | 类型 |
|---|---|---|
| step0 画像上下文 | `retrieve_memory` | 确定（读 semantic memory 入 OverAllState） |
| step1 QueryAnalyzer(LLM) | `planner` | LLM（含关键词规则回退，Q8 降级） |
| step2 RAG(确定) | `rag` | 确定 |
| step3 Stylist(LLM) | `stylist` | LLM |
| step4 Critic∥Trend | `critic` | LLM（Trend 并入 RAG 上下文，不再单独 LLM） |
| step5 Coordinator(LLM) | `responder` | LLM（格式化/发图） |

- `isSimpleRequest()`（formality≤3 且 subQueries≤3）→ StateGraph **conditional edge**，跳 critic/responder 走轻路径。
- 原 `CompletableFuture` 并行 Critic∥Trend → 废弃（Trend 已删）；Critic 打回走**回边 loop** 回 `stylist`。

#### C. 新增 / 改造

1. 引入 `spring-ai-alibaba-graph` 依赖（JDK 17+，项目 Java 21 满足）。
2. 新建图定义：组合 `OverAllState` + `StateGraph`，接 Redis checkpoint。
3. 实现 fashion 子图 6 节点（`retrieve_memory`/`planner`/`rag`/`stylist`/`critic`/`responder`）均为 `NodeAction`。
4. `planner` 节点 = LLM 路由 + 关键词规则回退，替代网关 38 条正则与 4 个 `maybeAutoX` 反射。
5. Guardrails：接框架 `maxIterations` + 自定义 maxSteps / token budget / 模型分级。

### 10.4 落地顺序（P0 起，每步 P5 守门）

| 阶段 | 动作 | 守门指标 |
|---|---|---|
| 1 | 引依赖，跑通官方 simple graph 样例 | 样例可编译运行 |
| 2 | 建 `OverAllState` + `StateGraph` + Redis checkpoint | 图可构建、状态可持久化 |
| 3 | 迁 fashion 子图；`AgentCoordinator` 标 `@Deprecated` 并行对照 | 单测 + 手测链路通 |
| 4 | P5 回归不退化 → 删 `AgentCoordinator`、清空 `runtime/` | **P5 top-5 不回退** |
| 5 | 迁 System B 工具层；跑通后删 `SpringAiChatCompletionsGateway` | P5 不回退 + 工具调用正常 |

> **P5 守门口径（修正）**：「P5 top-5 不回退」指**规则重排 P5 = 36.84%（21/57）**（`RuleRerankGridSearchLiveTest`，当前生产口径）。历史文档的 43.9% / 43.86% 系旧 RAGFlow 文档+旧标签的**跨期不可比**数字，不可作基线；检索口径 strict top-5 另为 24.6%（14/57）。运行：`RESUME_BENCH_LIVE=true RESUME_BENCH_RAG_PROVIDER=ragflow mvn test -Dtest=RuleRerankGridSearchLiveTest`，需 `logs/eval_rerank.tsv`（57 条）+ RAGFlow@9380 在线。rag/rerank 为确定性节点（§1 Q2 原地保留），迁移后同输入应得同分，守门 = 不退化即合格。
> 验收基线：简单请求仍 ≤2 次 LLM call、延迟不回归；Langfuse 全链路 trace 可见。

### 10.5 风险与待确认

- **框架成熟度**：`spring-ai-alibaba-graph` 仍在 FastIter（~1.1.0 RC），API 可能微调 → 阶段 1 先用官方样例验证，不假设接口稳定。
- **DeepResearch 仍仅 spec**（`docs/deepresearch-requirements.md`）：双项目"跨生态框架"叙事有一半依赖它。要么尽快启动，要么暂不作为主卖点。
- **新增依赖成本**：构建体积/学习曲线上升，但换来"不再维护两套自研编排器"——净收益为正。
- **cutover 期间双代码并存**：阶段 3 允许 `AgentCoordinator` 与新 graph 并行，但仅作对照；阶段 4 必须删除，否则回到四不像。

### 10.6 确认清单（请逐项勾选）

- [ ] 路线①（采用 spring-ai-alibaba-graph）确认
- [ ] 9 个手搓骨架删除确认
- [ ] System A/B 退役方式确认（@Deprecated 并行 → P5 守门后删）
- [ ] 阶段 1 先跑官方样例、验证 API 稳定性
- [ ] P5 基准脚本就绪（阶段 4/5 守门用）：基线已重锚为 **36.84%（规则重排，21/57）**，非旧 43.9%（跨期不可比）
