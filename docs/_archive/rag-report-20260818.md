# 穿搭助手检索系统优化报告

> 基于对 `ai/fashion/look/rag` 代码、RAGFlow/MySQL FTS 链路、以及 `logs/p0~p4*` 评测日志的梳理。
> 最后更新：2026-08-22（已落地 outfit 级聚合改造）。

---

## 一、当前架构速览

| 层级 | 组件 | 说明 |
|------|------|------|
| 查询理解 | `QueryAnalyzer` | LLM 输出 `originalQuery` + `decomposedQueries` + `params(scene/season/styleHint/formality/gender)`；持久缓存（P1.5）减少 LLM 漂移 |
| 主检索 | `RagFlowKnowledgeService` | 调用 RAGFlow `/api/v1/retrieval`，检索词 = `originalQuery + 场景:SCENE + 风格:STYLE + 季节:SEASON` |
| 降级检索 | `MysqlFtsKnowledgeService` | MySQL 8 `FULLTEXT ngram` 表 `fashion_seed_fts`，布尔模式全文检索 |
| 后处理 | `RetrievalDiversitySampler` | 候选池（默认 15）内按 score²+0.01 加权随机抽样，降低跨次重复 |
| 上下文 | `formatContext` | 从 `data/fashion_docs/outfit_*.md` 补全整套搭配概览，避免 chunk 信息残缺 |

生产配置：`top-k=5`、`similarity-threshold=0.2`、`vector-similarity-weight=0.3`、rerank 关闭、多样性开启。

---

## 二、已跑过的实验与关键数字

| 实验 | 样本 | 关键结果 | 结论 |
|------|------|----------|------|
| P0 检索缺口诊断 | 57 条真实查询 | top-1 15.8%，top-5 **61.4%**，rank 6~20 有 22 条，池外 0 条 | 排序问题为主，召回基本够 |
| P1 vector weight grid | 57 条 | vw=0.3 top-5 40.4%；vw=0.5/0.6 top-5 42.1% | 调权重收益很小 |
| P2 rerank AB | 57 条 | baseline 45.6% → rerank 26.3%（退步 15 条） | 通用 rerank 模型不适合本场景 |
| P2 rerank + outfit 聚合 | 57 条 | baseline 43.9% → rerank 22.8% | 聚合后依然退步 |
| P3 query 改写 | 57 条 | V1 现状 45.6%；V4 多路合并 36.8% | 简单 round-robin 合并引入噪声 |
| **P4 outfit 级聚合（生产链路）** | 57 条 | **top-1 15.8%，top-5 43.9%**，排序问题 31 条，召回缺失 1 条 | 这是真实 outfit 级 baseline |
| **P5 结构化规则重排 grid search** | 57 条 | **top-5 43.86% → 43.86%**，1215 组权重无净提升 | 当前 outfit 标签体系无法支撑规则重排 |

> ⚠️ **关键发现：P0 的 61.4% 是 chunk 级评估，虚高**。改成 outfit 级后，真实 baseline 是 **43.9%**（与 P1/P2/P3 的 40~45% 区间吻合）。这说明之前同一个 outfit 的多个 chunk 确实占用了 top-5 槽位，导致 chunk 级命中率被高估约 17pt。

---

## 三、诊断：为什么现有优化手段无效

### 3.1 Rerank 反而退步的根因

RAGFlow 返回的是 **chunk（单品段落）**，但用户最终要的是 **outfit（整套穿搭）**。同一个 outfit 会被切成多个 chunk，导致：

1. **粒度错位**：rerank 模型对单品片段打分，无法感知整套搭配是否与 query 匹配。
2. **分数量纲冲突**：通用 rerank 模型（gte-rerank-v2）未在穿搭语料上微调，容易把"海边白色连衣裙"这种局部关键词误排靠前。
3. **top-5 判定偏差**：生产返回的是 chunk，但实际应看 outfit 是否命中；直接用 chunk 序评估会低估真实可用率。

### 3.2 多路合并失败的根因

V4 把 V1/V2/V3/rawQuery 四路结果简单 round-robin 合并。问题：
- V2（中文映射）、V3（decomposedQueries）本身命中率低于 V1；
- 合并时高相关 outfit 被排在后面，反而被截断；
- 没有按相关性加权融合，只是机械去重。

### 3.3 vector weight 不敏感的根因

`vector_similarity_weight=0.3` 已经偏关键词。穿搭 query 中"海边/婚礼/爬山"等意图词和"黑色/白色/短袖"等属性词都依赖关键词匹配，继续调权重的天花板很低。

---

## 四、可落地的优化方向

### 4.1 统一评测口径（最高优先级）

1. **固定 QueryAnalyzer 输出**：所有对比实验使用同一份 `AnalyzedQuery`，建议从 `fashion_query_analysis_cache` 导出并版本化（如 `logs/eval_v2.tsv`）。
2. **处理同一 query 多 gt**：`fashion_conversations` 中"参加婚礼怎么穿"出现 4 次，gt 分别为 139/185/183/147。应区分：
   - 会话级 gt（该次推荐系统实际采用的编号）
   - query 级 gt（该 query 所有被采纳过的编号集合）
3. **outfit 级评估**：RAGFlow 返回 chunk，但统计命中时应按 outfit 聚合，避免粒度错位。
4. **纳入候选池召回率**：不仅看 top-5，还要看 gt 在 top-20/top-50 是否被召回，区分"排序问题"和"召回问题"。

### 4.2 Outfit 级检索与排序（已落地）

**已改造完成**：

1. `RagFlowKnowledgeService` 现在请求 `page_size=50` chunk，返回前按 outfit_id 聚合，取每个 outfit 的最高 similarity chunk 作为代表。
2. `RetrievalDiversitySampler` 现在工作在 outfit 层；关闭多样性时直接取 top-k outfit。
3. `MysqlFtsKnowledgeService` 同步按 outfit_id 去重，保持两套链路语义一致。
4. 新增配置 `app.fashion.rag.ragflow.retrieval-page-size=50`（默认 50）。
5. 新增日志：`RAGFlow retrieved {} raw chunks, aggregated into {} outfits`。

**实测结果**：
- 生产链路 outfit 级 top-5 命中率：**43.9%**（chunk 级虚高数字为 61.4%）。
- 50 个 chunk 通常聚合成 26~50 个 outfit，说明 chunk 碎片确实严重。
- 排序问题 31 条（可救），召回缺失仅 1 条。

**下一步**：基于这个真实 baseline 做后续优化，避免再用 chunk 级数字误导决策。

### 4.3 查询改写真正服务于召回

当前 `decomposedQueries` 生成后几乎没被使用（仅在 `originalQuery` 为空时兜底）。建议：

1. **子查询并行召回**：对 `decomposedQueries` 中 2~3 个子查询分别调用 RAGFlow，每路取 top-20 chunk。
2. **outfit 级融合**：把多路召回的 outfit 按出现次数 + 最高 score 融合排序（类似 Borda Count 或 RRF）。
3. **原始 query 作为一路**：保留原始 query 的直接召回，避免 LLM 改写丢失关键信息。
4. **避免 V4 式的简单轮询**：先按 outfit 聚合每路分数，再融合。

### 4.4 结构化元数据过滤

RAGFlow retrieval API 是否支持 `filters` 字段传递元数据？如果支持，建议：

1. 在文档解析时把 `scene/season/style/formality` 作为 chunk 的元数据写入。
2. QueryAnalyzer 提取的结构化参数不混入检索词，而是作为 `filters` 硬性过滤（如 season=SUMMER）。
3. 对"不要白色裤子"这类负向需求，在检索后做规则过滤，而不是加入检索词。

### 4.5 轻量级 Outfit 重排序

#### 4.5.1 结构化规则重排（已验证，当前标签体系下无净提升）

已实现的规则重排按 scene/season/style/formality 匹配度生成规则分，与语义分加权融合。
对 57 条真实查询跑了 **1215 组权重网格搜索**（semanticWeight 0.70~0.90，scene 0.40~0.60，season/style 0.10~0.20，formality 0.05~0.15，cap 0.50~0.80）。

**结果**：
- **top-5 命中率 43.86% → 43.86%**，无任何权重组合能超过纯语义 baseline。
- top-1 从 15.79% 略降至 14.04%（最优配置下）。
- 婚礼等强场景 query 上，top 候选的 outfit 标签里没有 `FORMAL_EVENT`/`婚礼`，规则分的 scene 维度完全失效。

**根因**：
1. **标签粗糙**：绝大多数 outfit 标签是"日常/夏季/休闲/街头"，无法区分同一候选池内的 outfit。
2. **标签缺失**：婚礼、海边等强场景 query 的 gt outfit 反而被标成"日常"，规则重排无法识别。
3. **语义分过于密集**：top-50 候选的 similarity 差距常在 0.005 以内，微弱的规则分就能造成扰动，但方向不可控。

**决策**：保留代码与配置门控，**默认关闭**（`rule-rerank-enabled=false`），避免无效改动和潜在退化。待 outfit 标签质量提升后再复测。

#### 4.5.2 LLM 重排

对 top-20 outfit 调用一次 LLM 做 listwise 精排，把 query + 候选 outfit 完整描述一起输入，让 LLM 判断哪套最匹配。

**优势**：不依赖粗糙标签，可利用颜色、风格、场景、用户偏好等全部文本信息。
**成本**：每条 query 多一次 LLM 调用（约 2~5k tokens）。
**建议**：作为可选策略，对复杂/高价值 query 开启；先做离线 A/B 验证收益是否覆盖成本。

#### 4.5.3 训练专用模型

收集更多标注数据后，用 `text-embedding-v3` 做 outfit 向量 + 轻量 MLP/GBDT 打分模型，长期最优。但当前样本量 57 条，不足以训练。

### 4.6 负反馈与偏好过滤

用户常说的"不喜欢白色/不要白色裤子"当前未有效处理。建议：

1. 在 `QueryAnalyzer` 中显式抽取 `excludedColors`、`excludedItems`。
2. 检索后过滤：把包含 excludedColors/excludedItems 的 outfit 从 top-k 中剔除（或降权）。
3. 对历史推荐过的 outfit 用 `excludeIds` 硬性排除，已部分实现，可确认是否生效。

### 4.7 MySQL FTS 降级链路增强

当前降级链路较简单，主要风险：RAGFlow 不可用时检索质量骤降。建议：

1. **增加字段权重**：`MATCH(content) * 2 + MATCH(style, scene, season)` 让单品描述权重更高。
2. **支持布尔模式短语查询**：对 styleHint 中的复合词加 `""` 短语匹配。
3. **接入 QueryAnalyzer**：当前 `MysqlFtsKnowledgeService` 的 `buildFtsPlan` 用 `decomposedQueries` + `originalQuery` + 标签映射，但未使用 `originalQuery` 为空时的场景参数增强，可对齐 RAGFlow 的检索词构造。

### 4.8 性能与成本优化

1. **RAGFlow 结果缓存**：对相同检索词的结果缓存 5~30 分钟，降低重复调用。
2. **候选池大小调优**：当前多样性 candidatePool=15。如果改为 outfit 级聚合，候选池可能需要调整为 30~50 chunk 才能保证足够 outfit 覆盖。
3. **超时与降级**：RAGFlow 超时 15s 较长，可设置 5s 快速超时后切 MySQL FTS。

---

## 五、推荐执行顺序

| 优先级 | 动作 | 预期收益 | 工作量 |
|--------|------|----------|--------|
| P0 | 统一评测口径：固定 analyze 结果 + outfit 级评估 | 让后续实验可比较 | 小 |
| P1 | 实现 outfit 级聚合排序 | top-5 命中率提升 5~15pt | 中 |
| P2 | 子查询并行召回 + outfit 级融合 | 召回率提升，尤其复杂 query | 中 |
| P3 | 结构化规则重排 | ~~排序问题显著改善~~ 已验证：当前标签体系下无净提升 | 小 |
| P4 | 负反馈过滤（颜色/单品排除） | 用户满意度提升，可直接解决具体 bad case | 小 |
| P5 | Outfit 标签补全/质量治理 | 为规则重排和元数据过滤打好基础 | 中 |
| P6 | 探索 RAGFlow 元数据过滤 | 长期检索精度提升 | 中 |
| P7 | MySQL FTS 增强 + RAGFlow 缓存 | 稳定性与性能 | 小 |
| P8 | LLM listwise 重排 | 当前数据下最有希望突破天花板 | 中 |
| P9 | 专用 rerank 模型 | 长期最优，需更多标注数据 | 大 |

---

## 六、关于简历数据的建议

当前简历写"召回效果满足业务需求"，比较虚。统一口径并完成 P1 后，可替换为：

- 真实用户查询 top-5 outfit 命中率：**43.86%**（25/57，outfit 级聚合后，chunk 级虚高 61.4%）
- 候选池召回率：top-50 覆盖 **92.98%**（53/57），top-20 覆盖 **82.46%**（47/57）
- 排序可救比例：rank 6~20 的 gt 占 **38.60%**（22/57），规则重排因标签粗糙未能救回
- 检索 P99 延迟：RAGFlow 平均 ~500ms / 降级 MySQL 平均 ~50ms
- 故障降级成功率：RAGFlow 不可用时 MySQL FTS 兜底可用（未量化成功率）

> 规则重排（P3）已验证无净提升，不要写进简历。下一刀若LLM重排或颜色过滤跑正收益，再替换/追加数字。
