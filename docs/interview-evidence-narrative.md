# 项目证据核销稿（10 分钟面试讲述版）

> 微信 AI 穿搭助手（WeChatBot）——Agent 应用实习项目证据底稿。
> 数据全部来自 2026-08-31 ~ 09-01 实测，均可复现（命令附后）。
> 使用原则：**讲归因，不背数字；被追问时给出处，被质疑时能重跑。**

## 一句话定位

> 一个生产级微信对话 Agent：**spring-ai-alibaba-graph 单图脊梁 + 6 节点编排 + RAG 检索 + 衣橱 DDD 引擎**，
> 从手搓多 Agent 框架迁移到图运行时（影子对照验证 → 权威接管），配套 unit 462 + integration 48 项测试、量化守门与可观测基建。

## 六维证据速览

| 维度 | 关键数字 | 一句话归因 |
|---|---|---|
| 检索质量 | 单路重排 **top-5 37.29%（22/59）** ≥ 基线 36.84%；召回（gt 进池率）**91.7%**；重排 +13.33pp；**MRR 0.18 / NDCG@10 0.22**（原始序） | 瓶颈在 rerank 排序，不在召回 |
| 生成质量 | **LLM-as-judge：完整管线 55%（33/60）胜裸基线**；0 降级 | 管线有增益但不压倒性；天气场景裸基线反杀（详见短板） |
| 韧性 | **故障注入 4/4**：Stylist/Coordinator/Critic∥Trend/RAG 挂 → 不崩 + 定向降级 | 降级是设计态，不是补丁 |
| 性能 | 图管线 **p50 9.1s / p95 24.6s**（n=60 实测） | 串行 5-6 次 LLM 调用是主因；critic∥trend 已并行 |
| 成本 | Langfuse 实测 3 次 generation ≈ 输入 3,112 + 输出 965 tokens ≈ **¥0.008** | flash 快模型主链路，可审计 |
| 工程治理 | unit 462 + integration 48 测试 / ArchUnit 边界 / Redis checkpoint 恢复 / 序列化 round-trip / 影子→权威迁移 | "正经工程"而非 demo |

## 开场五分钟：系统怎么设计的（对着图讲）

1. **入口**：微信 iLink → 对话控制层（RoutingLlmGateway 意图路由 + ToolRegistry 41 工具按意图裁剪）。
2. **心脏**：Fashion Graph 6 节点（retrieve_memory → planner → rag → stylist → critic∥trend → responder），
   简单请求在 stylist 后直接短路（省 2-3 次 LLM）。
3. **分治**：Look 引擎用多 Agent LLM（不确定性任务），衣橱引擎用 DDD + 确定性 8 维评分（规则任务），ArchUnit 锁死依赖方向。
4. **底座**：AgentLlmCaller 统一超时/重试/宽松 JSON/模型分档；Redis checkpoint 支持重启恢复。

**讲这个架构时钉住三个"不像实习生的点"**：
- 架构迁移方法论：feature flag 影子对照 → 逐项验证 → 权威接管 → 删除旧系统（System A + 9 个手搓骨架）。
- 状态序列化铁律：富对象过 Redis checkpoint 会退化成 LinkedHashMap，全链路 JSON 字符串化（真踩过的坑）。
- 确定性 vs 多样性：QueryAnalyzer 用 temperature=0 贪婪采样，实测否则检索波动 ±37pt。

## 后五分钟：拷打应答模板

### Q1 "这不就是调框架吗？agentic loop 你自己写过吗？"
- 承认图运行时是框架，但区分三层：拓扑（何时调谁）用图、推理（怎么想）是自研 Agent 类、底座（怎么安全调）是自研 AgentLlmCaller。
- 手撕 ReAct/状态机：一轮 LLM → 工具结果回填 → 条件路由 → 中止条件（max rounds=4 / recursionLimit=25）。
- 拿序列化坑证明深度：框架的 checkpoint 序列化器不保留嵌套 record，我们设计了约定（String + JSON 字符串 + 共享 ObjectMapper）并写了 round-trip 测试。

### Q2 "怎么证明系统真的有效，而不是自嗨？"（核心）
- 三个独立证据：① **LLM-as-judge**：60 条真实 query，完整管线 vs 裸基线，55% 胜，0 降级；② **P5 检索守门**：重排 top-5 37.29% ≥ 基线，召回 89.83%，逐 query 归因；③ **单变量实验**：改 prompt 只重刷方案A、基线 B 冻结原文，天气场景 2/7→3/7 验证了"温度硬约束"是弱杠杆。
- 主动讲归因：哪里赢（穿搭推荐 29/21）、哪里输（天气 2/7）、为什么（RAG 条目被"如实描述"铁律锁死，模型选择合理化长袖而不是替换）。

### Q3 "有什么短板？"（主动爆料，展示诚实）
- **天气场景裸基线反杀**：裸 LLM 更懂"33°C 要清凉"——管线被"图片一致性"铁律拖住了挑选自由度。
- **延迟 p95 24.6s**：串行 LLM 链，还有降本空间（简单请求仍跑 planner/rag 3 节点）。
- **成本分档不统一**：System B 有 simple/standard/complex 分档，图内用统一 maxTokens，两套口径。
- **数据规模**：161 条 outfit / 60 条 query——实习产出定位（展开见下方专节）。

### Q3.5 "数据才 161 条，没在真实规模下跑过吧？"（规模短板专用回应，三招）

**第一招 · 承认 + 定位**（主动说，别等被坑）：
> "数据 161 条是实习产出的真实边界，我没有编业务指标。我证明的是**评估方法**——守门、归因、否决实验这套纪律在数据规模上是 **scale 无关**的。"

**第二招 · 把劣势转成方法论卖点**：
- 小数据下单变量实验反而更干净：10 条天气 query 就否决了温度重排信号；60 条 query 的 Recall 我**自己把 ±5pp 标准误标在指标表里**（`logs/rag_standard_metrics.md`）——"我知道小样本的不确定性，并主动量化了它"。
- 评估管线数据无关：TSV → MRR/NDCG 的脚本换十万级语料一行不改。

**第三招 · 规模化预案**（讲方向，不装做过）：
> "给我十万级电商语料，动作是：先跑现成脚本出基线 → 上多路召回（BM25+dense）看 Recall@50 能否从 91.7% 往上推 → 再评估重排。指标体系和流程已就位，缺的只是数据。"

**五句话浓缩版**：
> "召回侧 Recall@50=91.7% 健康，重排把 top-5 从 23% 抬到 37%（+13pp），MRR 0.18 / NDCG@10 0.22 是原始序口径；我否决过 4 类重排信号，每个都有离线复现记录。数据规模 161 条是实习边界，置信区间我在指标表里自己标了——方法论 scale 无关，换语料即跑。"

> 口径提醒：MRR/NDCG 是**语义原始序**（=无重排 baseline）算的；重排后整体更高（Recall@5 抬 ~13pp）。讲的时候说清楚是原始序，别让面试官抓到指标口径混用。

### Q4 "挂了怎么办？"
- 四类故障注入测试全绿：Stylist 挂→安全兜底；Coordinator 挂→降级为 Stylist 首选（文案区分超时/异常/无输出）；评审并行挂→中性降级；RAG 挂→空上下文继续。
- Redis checkpoint 同 threadId 重启可恢复中间状态。
- 降级文案三态化是 09-01 修的真 bug（之前一律写"超时"误导排查）。

### Q5 "成本多少？"
- Langfuse OTLP 链路实测打通（两个坑：认证要 Basic 不是 Bearer；Spring AI 默认不落 usage 需补属性）。
- 口径：生成用 qwen3.7-flash（专线），评审/分析用 qwen3.7-plus；首笔核销 ≈¥0.008/几次对话，可审计。

## 可复现命令（全部真实执行过）

```bash
# P5 检索守门（单路口径）
RESUME_BENCH_LIVE=true RESUME_BENCH_RAG_PROVIDER=ragflow BENCH_SINGLE_ROUTE=true mvn test -Dtest=RuleRerankGridSearchLiveTest

# 标准检索指标（MRR/NDCG/Recall@k，离线）
python scripts/rag_standard_metrics.py        # → logs/rag_standard_metrics.md

# 温度重排可行性（已否决，留证据）
python scripts/p5_temp_signal_check.py

# 生成质量：成对方案 + LLM-as-judge
RESUME_JUDGE_DATA=true mvn test -Dtest=JudgePairDataLiveTest        # 60 条真实 query，约 20-40 分钟
python scripts/agent_quality_judge.py [--limit 10]                  # judge 裁决（幂等可续跑）

# 韧性：故障注入
mvn test -Dtest=FaultInjectionGraphTest

# 性能：延迟聚合（复用 judge_pairs 时延）
python scripts/latency_aggregator.py

# 成本：Langfuse 核销（需 OTLP env + pub/secret key）
RESUME_LANGFUSE_SMOKE=true mvn test -Dtest=LangfuseTraceSmokeLiveTest
LANGFUSE_PUBLIC_KEY=pk-xxx LANGFUSE_SECRET_KEY=sk-xxx python scripts/langfuse_cost_audit.py
```

## 诚实的边界（主动说，别等被问）

- 项目是**实习/个人产出**，没有真实用户满意度与留存数据——用检索指标 + 生成评估 + 可复现实验撑可信度，**明确不编业务数字**。
- 两个引擎（LLM vs 规则）的并存是分治取舍，可讲清楚边界；图内工具循环只有天气 1 个工具，是 POC 不是全量闭环。
- 面试重点不是"系统完美"，而是"我知道它哪里强、哪里弱、为什么"。