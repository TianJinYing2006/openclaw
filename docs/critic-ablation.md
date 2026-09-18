# Critic 消融实验（Evaluator-Optimizer 是否值得）

> 问题：多 Agent 管道里的 Critic/Trend/Coordinator 到底有没有正收益？还是只增加延迟与成本？
> 本文给出**实验设计 + 已完成的确定性结构消融结果 + live 质量消融的运行方式**，不伪造质量数字。

---

## 1. 四组对照配置

| 配置 | 组成 | 目的 |
|---|---|---|
| C1 | Stylist | 基线 |
| C2 | Stylist + Critic | 验证 Critic（含打回重生成） |
| C3 | Stylist + Trend | 验证 Trend |
| C4 | Stylist + Critic + Trend + Coordinator | 验证完整链路 |

固定同一批查询（`src/test/resources/golden/ablation_queries.txt`，10 条，覆盖简单/复杂/正式/休闲/天气）。

---

## 2. 指标

- **确定性（可自动断言）**：Agent 调用次数、完成率、是否含 `referenceOutfitId`、方案数、critic 评分、降级率、墙钟 p50/p95。
- **质量（需 LLM-as-judge 或人工）**：约束满足率、用户偏好命中率、衣橱真实物品命中率、幻觉率、人工评分。
  质量指标不在此轮 mock 中伪造；live 报告留出字段，接 `scripts/agent_quality_judge.py`。

---

## 3. 已完成：结构/成本消融（mock，确定性）

`FashionAgentAblationTest`（unit profile）在同一 query 上只切换 planner 判定（简单/深），运行**真实子图**：

| 配置 | Stylist | Critic | Trend | Coordinator | 合计 Agent 调用 | 降级 | 墙钟（mock） |
|---|---|---|---|---|---|---|---|
| stylist-only（简单路径） | 1 | 0 | 0 | 0 | **1** | false | ~噪声 |
| deep（stylist+critic+trend+coordinator） | 1 | 1 | 1 | 1 | **4** | false | ~噪声 |

**结论（结构侧）**：
- 评审链路使每请求多 **3 次 Agent 调用**（critic / trend / coordinator）。
- 这正是设计上「**简单请求跳过评审**」（`isSimpleRequest`）与「**critic 回环默认关闭**」
  （`app.fashion.graph.critic-loop.enabled=false`）的成本依据。
- mock 下墙钟为图编排开销，**不能**当作真实时延；真实时延见 `docs/quantitative-metrics.md`。

报告产物：`target/golden/critic-ablation-structural.md`（由测试生成）。

---

## 4. 待运行：质量消融（live）

`FashionAgentAblationLiveTest`（`-Plive` + 环境变量门控），用真实模型跑 C1~C4：

```bash
# 前置：MySQL/Redis 已启动；模型密钥与 RAGFlow 已配置
FASHION_ABLATION_LIVE=true mvn test -Plive -Dtest=FashionAgentAblationLiveTest
# 报告：target/golden/critic-ablation-live.md
```

该 harness 输出确定性指标（完成率 / 引用率 / 方案数 / critic 分 / 降级 / 墙钟），
质量维度需再跑 `scripts/agent_quality_judge.py` 做 LLM-as-judge。

### 决策规则（预先承诺，避免事后找理由）

- 若 C2 相对 C1 在**约束满足率 / 人工评分**上无显著正收益，且延迟/成本明显更高 →
  **保持 critic 回环默认关闭**，并在文档中如实记录负结论。
- 若 C4 相对 C2/C3 无增益 → 考虑将 Coordinator 降级为确定性合流。
- 任何「有收益」结论必须附：固定查询集、样本量、指标定义、p50/p95、Token 成本。

---

## 5. 实测结果

- 结构侧（mock）：评审链路 **+3 次 Agent 调用**（见 §3）。
- 质量侧（live）：已跑通 **两次有效对照**——A（MySQL FULLTEXT + `qwen3.7-plus`）、
  B（RAGFlow 生产口径 + `qwen3.7-flash`），详见 **[Critic 消融实测结果](critic-ablation-results.md)**。

**结论（据实测，两版一致）**：
- 延迟成本：`stylist-only` median ≈ 11.4~11.9s → `full` median ≈ 41.9~44.9s（≈3.5~3.9x）。
- 20 条 query 中 Critic **打回 0/10**、无降级，未观测到对输出的修正 → 质量增益无证据。
- 因此 **维持 `critic-loop` 默认关闭**；在 LLM-as-judge 质量证据出现前不宣称收益。
- 这一「不夸大、用数据决定」的立场，与项目中 rerank / query 改写被 A/B 否决的结论一致。
