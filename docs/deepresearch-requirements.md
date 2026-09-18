# DeepResearch 项目需求文档（v1.0）

> **文档性质**：需求冻结版，开发待启动（用户后期执行）。
> **作者/归属**：田金应（Agent 开发实习生求职能力证明项目）。
> **一句话定位**：用**一个真实的、带反思循环的自主研究型 Agent**，一次性补足简历中「框架诚信 / agentic vs 编排 / 主流可观测工具链 / 开源社区佐证」四项缺口。
> **与 WeChatBot 的关系**：WeChatBot 是固定 DAG 的业务型多 Agent 编排；DeepResearch 必须体现运行时动态决策 + 反思重试的**真·agentic** 行为，二者形成互补叙事。

---

## 0. 决策点（开发前由用户定稿）

| # | 决策项 | 建议默认 | 备选 |
|---|---|---|---|
| D1 | 开发语言 | Python 3.11+ | TypeScript |
| D2 | Agent 框架 | **LangGraph** | AutoGen / 原生 function-calling 循环 |
| D3 | 主力推理模型 | 强推理模型（Claude / GPT / Qwen-Max / DeepSeek-R1） | 同档更便宜型号 |
| D4 | 嵌入/检索模型 | 随 D3 供应商 | — |
| D5 | 工具来源 | Tavily(搜索) + Jina/自抓(抓取) + arXiv + 代码执行 | SerpAPI/Brave + Firecrawl |
| D6 | 可观测 | **Langfuse** | LangSmith / Phoenix |
| D7 | 交付 | GitHub 开源 + 技术博客 1~2 篇 | 仅私有 |

> 选框架的原则：**真用它的核心抽象**（LangGraph 就用 state + conditional_edge + checkpointer；AutoGen 就用 group chat / handoff），并能讲清"为什么选它、和你手写的区别"。禁止"只调一下"。

---

## 1. 背景与目标

### 1.1 为什么做
- 现有 WeChatBot 证明了「多 Agent 编排 + RAG + 自建 MCP + 评测纪律」，但本质是**固定 DAG 编排**，且简历曾误标 LangChain/LangGraph（已按方案 B 删除，三份副本已清理）。
- 本项目战略定位：用**真实可演示的 agentic loop 研究 Agent**，把下列缺口一次性补上：
  1. 框架诚信（做完可凭真实项目把 LangGraph 加回简历）；
  2. agentic 概念深度（plan → act → observe → reflect → 决策）；
  3. 主流可观测工具链（Langfuse）；
  4. 开源/社区佐证（GitHub + 博客）。

### 1.2 成功标准（Definition of Done）
- [ ] 端到端接收一条研究型问题，输出带引用溯源的结构化报告；
- [ ] 过程中模型**自主决定**检索策略与何时停止（非固定步骤）；
- [ ] 至少含一个反思/自我纠错循环（信息不足 → 补搜 / 换角度）；
- [ ] 接入 Langfuse，单次 research 全链路 trace 可回放；
- [ ] 有 ≥20 条评测集 + 量化指标；
- [ ] GitHub 开源，附 README + 至少 1 篇技术博客。

---

## 2. 用户与场景

- **主要用户**：研究者 / 分析师 / 求职者本人（演示）+ 面试官（审阅代码）。
- **典型输入**：
  - "对比 RAG 与长上下文在代码问答任务上的优劣，给出选型建议"
  - "总结近一年关于 LLM agent 评估方法的论文进展"
  - （多轮）"上面第 2 篇的方法在中文场景是否成立？"
- **非目标**：实时对话机器人、多模态生成、商业化爬虫。

---

## 3. 核心概念定义（必须贯穿实现）

**Agentic DeepResearch = plan（规划）→ act（调工具检索/计算）→ observe（观察结果）→ reflect（反思：覆盖度/矛盾/缺口）→ 决策 continue / revise / stop → synthesize（综合报告）** 的闭环。

- **与 WeChatBot 的关键区别**：WeChatBot 是「固定 5 步 + 确定性路由」；本项目必须「运行时动态决策 + 反思重试」。
- **验收雷区**：面试官必问"模型怎么决定下一步、怎么判断该停"——这两点必须有**真实代码实现**，不能靠 prompt 假装。

---

## 4. 功能需求（FR）

### FR1 输入与意图
- FR1.1 接收自然语言研究问题（支持多轮追问，携带上下文）。
- FR1.2 可选：接受初始约束（时间范围、语言、来源偏好、输出格式）。

### FR2 规划（Planner）
- FR2.1 将问题拆为子问题 + 检索计划，输出有序子任务。
- FR2.2 计划可随反思结果**动态修订**（不是一次生成定死）。

### FR3 检索与工具调用（Researcher / Tool Loop）
- FR3.1 Web 搜索工具（Tavily 等）返回带 URL 的结果。
- FR3.2 网页抓取/读取工具，抽取正文。
- FR3.3 学术检索（arXiv / Semantic Scholar）。
- FR3.4 代码执行工具（Python sandbox）用于数据/计算型子问题。
- FR3.5 工具调用为**循环**：模型依据当前状态决定调哪个、调几次，直到满足 Critic 的停止条件。

### FR4 反思与决策（Critic / Reflector）
- FR4.1 评估已得证据：覆盖度、矛盾、可信度、缺口。
- FR4.2 输出决策：继续检索 / 换角度 / 停止并进入综合。
- FR4.3 含最大步数 / token 预算上限的兜底终止。

### FR5 综合与输出（Synthesizer）
- FR5.1 生成结构化报告（摘要 / 分点结论 / 对比表 / 引用列表）。
- FR5.2 每个结论**可溯源**到具体来源 URL 或文献。
- FR5.3 输出 Markdown（可扩展 PDF）。

### FR6 记忆（Memory）
- FR6.1 跨步骤携带状态（已检索、已否定方向、用户偏好）。
- FR6.2 多轮会话上下文延续。

---

## 5. 非功能需求（NFR）

- **NFR1 可观测**：Langfuse 记录每步 input/output/token/工具调用/trace。
- **NFR2 评估**：内置 eval 脚本 + ≥20 条标注集（指标见 §7）。
- **NFR3 成本与延迟**：单次研究设 token / 步数预算；报告 p95 延迟上限（如 3~5 分钟）。
- **NFR4 健壮性**：单工具失败不影响整体（降级/重试）；来源失败跳过并标注。
- **NFR5 可复现**：固定模型版本、提示词版本、工具版本；README 含运行步骤。
- **NFR6 合规**：仅抓可公开内容，遵守 robots / 速率限制；引用保留来源。

---

## 6. 建议架构

```
User Query
   │
   ▼
[Planner]──┐
   │       │ (revise plan)
   ▼       ▲
[Researcher / Tool Loop]──► Tools: WebSearch / Fetch / arXiv / CodeExec / RAG
   │
   ▼
[Critic / Reflector]──decision──► continue │ revise │ stop
   │                                     ▲
   │ (loop until stop) ───────────────────┘
   ▼
[Synthesizer]──► Markdown Report + Citations
   │
   ▼
[Langfuse trace]  +  [Eval harness]
```

- **状态（State）**：当前问题、子任务列表、已检索证据、反思日志、用户约束。
- **LangGraph 实现**：节点=上述角色；`conditional_edge` 实现 Critic 的 continue/revise/stop；`checkpointer` 实现记忆。
- **原生循环实现**：`while not stop: plan / act / observe / reflect`。

---

## 7. 评测方案

- **评测集**：≥20 条，覆盖 易 / 中 / 难 + 多轮追问 + 需计算 / 需学术检索 等类型。
- **指标**：
  - 完成率（是否产出可用报告）
  - 引用准确率（结论能否对应真实来源；LLM-as-judge + 人工抽检 20%）
  - 信息覆盖度（子问题是否都被回答）
  - Token 成本 / 平均步数（效率）
  - 反思有效性（该停时是否停，避免无限循环）
- **流程**：eval 脚本批量跑评测集 → 输出指标表 → 人工抽检。

---

## 8. 验收清单（DoD 复核）
（同 §1.2 的 checklist，开发完成后逐条打勾。）

---

## 9. 简历回填口径（做完后）

技能栏可新增/恢复：
- `Agent 框架：LangGraph（deepresearch：plan-act-reflect 循环 + 工具自决 + Langfuse 全链路 trace）`

项目栏新增 DeepResearch 一条，写：自主研究型 Agent、反思循环、工具自决、可观测、量化评测、开源。
- **只写真用且能展开的内容**，勿过度标注（吸取 WeChatBot 简历误标教训）。

---

## 10. 里程碑（建议）

| 阶段 | 目标 |
|---|---|
| M0 骨架 | 单轮 query → Planner → 1~2 个工具 → 报告（无反思） |
| M1 反思循环 | Critic 决策 continue/stop，闭环跑通 |
| M2 工具集齐 | 搜索 + 抓取 + 学术 + 代码执行 |
| M3 可观测 | Langfuse 接入 |
| M4 评测 | eval 集 + 指标 + 报告 |
| M5 开源+博客 | GitHub + README + 1~2 篇技术博客 |

---

## 11. 风险与反模式

- ❌ **换汤不换药**：固定几步搜完就总结 = 又一条 DAG，不补缺口。
- ❌ **框架只"调一下"**：用 LangGraph 却没用 state/conditional_edge，面试官一问就露。
- ❌ **无 eval**：做完说"效果不错"但拿不出数字。
- ❌ **重新过度标注**：简历又写没用过的东西。
- ❌ **无限循环**：缺步数 / 预算兜底。

---

## 附录：与 WeChatBot 对照（面试话术素材）

| 维度 | WeChatBot | DeepResearch |
|---|---|---|
| 编排方式 | 固定 5 步 DAG + 确定性路由 | 运行时动态决策 + 反思循环 |
| 工具调用 | @AgentTool 白名单、Spring AI 循环 | 模型自决调哪些工具、调几次 |
| 框架 | Spring AI（手写编排） | LangGraph（图 + 状态 + 条件边） |
| 可观测 | 内部 AgentTraceController + P5 脚本 | Langfuse 全链路 trace |
| 定位 | 业务型多 Agent 编排 | 自主研究型 Agent（agentic） |

> 面试主线话术："我之前在 WeChatBot 里做的是确定性多 Agent 编排；在 DeepResearch 里实现了带反思循环的自主 Agent，模型能自己决定检索策略和何时停止——两者对比，我清楚编排和 agentic 的边界。"
