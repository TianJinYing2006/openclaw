# Agent 评审证据清单（Evaluation Evidence Kit）

> 面向"这个项目到底行不行"的评审/拷打，本项目已沉淀的证据与待补缺口。
> 更新：2026-08-31（证据基建第一轮落地）

## 三层可信度原则

评审只看三种证据，按可信度递减：
1. **可复现实验**（L1）：测试、benchmark、离线脚本——任何人可重跑验证。
2. **生产观测**（L2）：真实流量下的日志、trace、指标——证明在真实环境成立。
3. **叙事**（L3）：代码讲解、复盘——不可独立验证，只用来解释前两层。

本项目 L1 已较扎实，L2/L3 此前几乎空白；本轮补 L1 的"生成质量"与"韧性"，并给出 L2 的操作路径。

## L1 · 可复现证据（已具备）

| 证据 | 位置 | 内容 | 怎么跑 |
|---|---|---|---|
| 单元测试（默认，全绿） | `src/test` | 390 用例，不依赖 MySQL/Redis/外部模型 | `mvn test`（= `-Punit`） |
| 集成测试 | `src/test` | ApplicationContext / 仓储 / 图 checkpoint，需 MySQL（+Redis） | `mvn test -Pintegration` |
| P5 检索守门 | `docs/quantitative-metrics.md` | 单路 37.29%（22/59）≥ 基线 36.84%；召回 89.83%；归因=rerank 排序瓶颈 | `RESUME_BENCH_LIVE=true RESUME_BENCH_RAG_PROVIDER=ragflow BENCH_SINGLE_ROUTE=true mvn test -Dtest=RuleRerankGridSearchLiveTest` |
| 归因脚本站 | `scripts/p5_recall_gap_analysis.py` `scripts/p5_rerank_attribution.py` | 召回缺口 / 重排可救性逐 query 分析 | `python scripts/p5_rerank_attribution.py` |
| Redis checkpoint 恢复 | `FashionGraphRedisRecoveryIntegrationTest` | 富类型经 Redis 往返 + 同 threadId 重启恢复 | `mvn test -Pintegration -Dtest=FashionGraphRedisRecoveryIntegrationTest`（需本地 Redis） |
| 序列化 round-trip | `StateSerializerRoundTripTest` | record→JSON 字符串→record 类型安全 | `mvn test -Dtest=StateSerializerRoundTripTest` |
| **韧性故障注入**（本轮新增） | `FaultInjectionGraphTest` | Stylist/Coordinator/Critic/Trend/RAG 注入故障 → 图不崩、降级符合设计（4/4） | `mvn test -Dtest=FaultInjectionGraphTest` |
| **生成质量 LLM-as-judge**（本轮新增） | `JudgePairDataLiveTest` + `scripts/agent_quality_judge.py` | 59 条真实 query：完整图管线 vs 裸基线 win-rate | 见下方步骤 |

### 生成质量评审（新增）

```bash
# 1. 生成成对方案（真实图管线 vs 裸基线，shadow 只读不写库，n=59，约 20-40 分钟）
RESUME_JUDGE_DATA=true mvn test -Dtest=JudgePairDataLiveTest

# 2. LLM-as-judge 裁决（qwen3.7-plus，可 --limit 小样本试跑，--resume 幂等续跑）
python scripts/agent_quality_judge.py [--limit 10]

# 3. 报告
logs/judge_summary.md        # win-rate + 意图分布
logs/judge_results.jsonl     # 逐条裁决（可复查）
```

顺带产出：`logs/judge_pairs.jsonl` 内的 `graphElapsedMs` 即端到端图管线时延样本（n=59），供延迟聚合复用。

### 端到端延迟聚合（新增）

```bash
python scripts/latency_aggregator.py   # 读 judge_pairs.jsonl → logs/latency_summary.md（p50/p90/p95，按意图分桶）
```

口径：graphElapsedMs 覆盖 retrieve_memory→planner→rag→stylist→critic∥trend→coordinator 全程（纯 Agent 管线，不含微信链路）。历史口径（2026-08-04 n=6，单次 LLM）见 quantitative-metrics.md。

## L2 · 生产观测（待补，分两步）

### 第一步：Langfuse token 成本核销（2026-09-01 已打通并有实测结论）

先在**本机**验证上报链路（避免直接污染生产）：

```bash
# 环境变量（开启观测时）：
export LANGFUSE_OTLP_ENABLED=true
export LANGFUSE_OTLP_ENDPOINT=<OTEL 端点>   # jp 区=https://jp.cloud.langfuse.com/api/public/otel
export LANGFUSE_OTLP_PROTOCOL=http/protobuf
# 🔴 认证用 Basic，不是 Bearer（实测：Bearer 401，Basic base64(pk:sk) 200）：
export LANGFUSE_OTLP_HEADERS="Authorization=Basic $(printf 'pk-xxx:sk-xxx' | base64 -w0)"
# trace 导出器门控：默认 none（防未配置时向 localhost:4317 发起失败导出），开启时必须覆盖为 otlp：
export OTEL_TRACES_EXPORTER=otlp
# management.tracing.enabled / otel.exporter.otlp.enabled / otel.exporter.otlp.endpoint 默认由 LANGFUSE_OTLP_ENABLED 联动，别动
```

运行观测链路冒烟（2 条真实 query，只出 trace 不落数据）：
```bash
LANGFUSE_OTLP_ENABLED=true LANGFUSE_OTLP_ENDPOINT=https://jp.cloud.langfuse.com/api/public/otel \
LANGFUSE_OTLP_HEADERS="Authorization=Basic ..." OTEL_TRACES_EXPORTER=otlp \
RESUME_LANGFUSE_SMOKE=true mvn test -Dtest=LangfuseTraceSmokeLiveTest
```

聚合 token 成本（读 trace 详情接口的 usage）：
```bash
export LANGFUSE_PUBLIC_KEY=pk-xxx LANGFUSE_SECRET_KEY=sk-xxx
python scripts/langfuse_cost_audit.py --limit-traces 20 --since 2026-09-01   # → logs/langfuse_cost_audit.md
```

**实测结论（2026-09-01）**：
- OTLP 端点认证必须 `Authorization: Basic base64(pk:sk)`（官方文档写 Bearer 是错的，jp 云区实测 401 vs 200）。
- Spring AI 1.1.8 的 ChatModel 观测**默认不带 token usage**——已在
  `ChatModelCompletionContentObservationFilter` 补挂 `gen_ai.usage.input_tokens/output_tokens` 属性后才落库。
- usage 只在单条 trace 详情接口（`GET /api/public/traces/{id}`）可见；`v2/observations` 批量接口不暴露。
- 冒烟实测：2 条 query → 若干 GENERATION span，首笔核销 ≈ 输入 3,112 / 输出 965 tokens（约 ¥0.008，flash 模型）。
  目标产出：按路径（简单/复杂）聚合的 `samples/cost_per_conversation`，补 quantitative-metrics.md §4 的"待核验"。

### 第二步：真实流量观测（需上线后）

- 端到端延迟：业务日志按 conversationId 聚合入口→响应（judge_pairs 只是管内线时延，真实链路还有微信收发）。
- 影子长期比对：本设计已随 AgentCoordinator 删除而不再可用；替代方案=图内 `app.fashion.graph.shadow=true` 跑对比批次（仅观测用）。
- 用量审计：`app.ai.usage.daily-token-limit` 为内存计数、重启清空；需持久化审计走 `JdbcUsageEventRecorder`（确认落库路径后补数字）。

## 现状缺口一览（评审易被拷打处）

| 维度 | 状态 | 缺口 |
|---|---|---|
| 检索质量 | ✅ L1 完整 | — |
| 生成质量 | ✅ 本轮补 L1（judge win-rate） | 需跑完出数 |
| 韧性 | ✅ 本轮补 L1（故障注入 4/4） | 可再补"真杀依赖"级混沌实验 |
| 性能 | ⏳ 半 | judge_pairs 出数后聚合 p50/p95 |
| 成本 | ❌ | 需 Langfuse key 核销 |
| 业务价值 | ❌ | 定位为实习产出，用"设计目标+验证"口径，勿编数字 |