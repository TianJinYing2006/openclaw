# Agent 质量评审（LLM-as-judge）实测 · 2026-09-18

> 用现有评测管线：`JudgePairDataLiveTest` 生成成对方案（A=完整图管线，B=裸 Stylist 基线），
> `scripts/agent_quality_judge.py` 做 LLM-as-judge。
> 判官只看用户可见文案；Path A 已改为**生产 `FashionResponseFormatter` 文案**（内部编号已清理）。

## 1. 配置

| 批次 | 检索 | 生成模型 | 判官模型 | 样本 | 备注 |
|---|---|---|---|---|---|
| 试点 | RAGFlow | `qwen3.7-flash` | `qwen3.7-plus` | 12（含重复） | 用于发现格式/样本问题 |
| **主实验 v2** | RAGFlow | `qwen3.7-flash` | `qwen3.7-plus` | **30（去重+清洗）** | 原 prompt，结论以此为准 |
| v3（尝试改进） | RAGFlow | `qwen3.7-flash` | `qwen3.7-plus` | 30（同批） | 加表达约束，未奏效已回滚 |

去重清洗：`GROUP BY user_input` + 过滤内部提示词泄漏行 + 轻量归一（合并「换一套冬天穿搭」类近似重复），
47 条原始 → 43 独立 → 取 30。A 侧降级 0/30，裁决失败 0/30。

## 2. 结果

**主实验（30 条，去重）**

| 结果 | 比例 |
|---|---|
| **A（完整图管线）胜** | **33.3%（10/30）** |
| **B（裸 Stylist 基线）胜** | **63.3%（19/30）** |
| 平局 | 3.3%（1/30） |

意图分布：天气 **A 0 / B 3**；穿搭推荐 A 9 / B 15（tie 1）；衣橱 A 1 / B 1。

**试点（12 条，含重复）**：A 1/12（8.3%） vs B 11/12。

### 2.1 公平性修正后再测（试点 12 条）

把 Path A 从「Coordinator 原始字段」改为生产 Formatter 文案后仅重生成 A、B 冻结：
A 仍为 **1/12**（个案各翻一条，总量不变）→ **负面结论不是格式 artifact**。

### 2.2 样本修正

试点样本重复严重（有效独立 query 仅 4~5 条）；主实验已去重到 30 条独立 query，结论更可信：
**完整管线仍明显落后于裸基线（33% vs 63%）**。

### 2.3 尝试改进 A 质量（v3）——未奏效，已回滚

针对归因，给 STYLIST/COORDINATOR 提示词加了「面向用户表达」约束（不复述画像、不泄漏内部编号、偏好隐含体现），
同口径重跑 30 条：

| 批次 | A 胜率 | B 胜率 | 平局 |
|---|---|---|---|
| v2 主实验（原 prompt） | 33.3% | 63.3% | 3.3% |
| v3（加表达约束） | **23.3%** | **76.7%** | 0% |

- 差异（10→7 胜）在 n=30 的噪声范围内，**无正收益证据**，且方向为负。
- 按项目「无正收益即回滚」原则，**已撤销该提示词改动**；保留的是已验证、工程上正确的
  `FashionResponseFormatter` 内部编号清理。
- 结论：**单纯改提示词不足以把管线质量拉回**；需要改的是架构/输出范式（简单意图轻量路径、
  画像使用方式），而非措辞。

## 3. 归因（待进一步验证）

抽查 A/B 原文（`logs/judge_pairs_v2.jsonl`，本地 gitignore）：
1. **A 文本仍含画像/知识库措辞**：Formatter 清理了 `outfit_XXX`，但理由里仍可见对用户偏好的直接复述，
   当画像不准时显得答非所问。
2. **A 在「天气」意图 0:3 全负**：疑似管线对天气类简单请求返回更啰嗦/结构化，而基线更直接。
3. **B 的天然优势**：裸 Stylist 只回答搭配本身，语言自然、无系统痕迹。

## 4. 结论

- **在 30 条去重真实 query 上，完整图管线的用户可见文本明显不如裸 LLM 基线**（33% vs 63%）。
  这是本阶段最硬的负面数据，与 rerank / query 改写被 A/B 否决一脉相承。
- **对项目叙事的修正**：不应宣称「多 Agent = 更好」；评审链路目前是**未证明收益的成本**，
  应保持默认关闭（与 `critic-loop` 默认 false 一致）。
- **已完成的改进**：`FashionResponseFormatter` 剥离用户可见文案中的 `outfit_XXX` 内部标记
  （回归 `FashionResponseFormatterTest.stripsInternalOutfitMarkersFromUserFacingText`）。
- **已尝试但回滚**：v3 给 STYLIST/COORDINATOR 加「面向用户表达」约束，A 由 33% 降至 23%
  （噪声内、无正收益）→ 回滚，不动生产提示词。
- **下一步（架构/范式层，非措辞）**：
  1. 简单/天气意图走**轻量路径**（少注入画像与 RAG、跳过精炼），避免过度加工；
  2. 画像改为**隐式使用**（改注入策略/字段，而非 prompt 措辞）；
  3. 改进后同口径复测；若仍不优于基线，则如实保留「评审链路默认关闭」的产品决策。

## 5. 与其它评测的关系

- 延迟/成本消融：`critic-ablation-results.md`（评审链路 3.5~3.9x 延迟、Critic 打回 0/20）。
- 本文：用户可见文本质量。两者共同指向「多 Agent 评审链路收益未被数据证明，且输出质量有可优化空间」。

## 6. 复现

```bash
# 生成（去重版，30 条）：
RESUME_JUDGE_DATA=true RESUME_JUDGE_LIMIT=30 RESUME_JUDGE_OUT=logs/judge_pairs_v2.jsonl \
  RESUME_STUDY_RAG_PROVIDER=ragflow mvn test -Plive -Dtest=JudgePairDataLiveTest \
  -DargLine="-Dapp.ai.fashion-model=qwen3.7-flash"
# 判官：
python scripts/agent_quality_judge.py --input logs/judge_pairs_v2.jsonl \
  --output logs/judge_results_v2.jsonl --summary logs/judge_summary_v2.md --limit 30 --model qwen3.7-plus
```
