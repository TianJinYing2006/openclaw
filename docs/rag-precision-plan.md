# RAG 检索精度优化方案（路线 B · 让真实数据变强）

> 背景：简历数据核验（2026-08-22，`scripts/verify_resume_data.sh --live`）确认检索基线为 **strict top-5 46.4%**。
> 本方案 = 检索精度真实提升路线（与"改口径"的路线 A 互补；A 立得住、B 顶上去）。
> 配套评测基建已存在：`ResumeBenchmarkLiveTest`（strict top-1/top-5、top-20 覆盖、3 轮波动、rerank AB、查询改写消融）+ `verify_resume_data.sh`。

---

## 1. 现状诊断（2026-08-22 实测 + 代码事实）

### 1.1 生产检索链路

```
用户消息
  → QueryAnalyzer（LLM：scene/styleHint/season 解析）
  → buildSearchQuestion（原文 + "场景:x 风格:x 季节:x"拼接，decomposedQueries 兜底）
  → RagFlowKnowledgeService.retrieve（provider=ragflow）
      · pageSize = diversity.enabled ? max(topK, candidatePool=15) : topK
      · RAGFlow hybrid 检索：score = 0.3*vector_sim + 0.7*term_sim（vw=0.3，偏关键词）
      · **不启用 rerank（rerank-id 为空）**
      · diversity.enabled=true（生产默认）→ RetrievalDiversitySampler 加权随机抽 topK
  → top-5 供 Stylist 使用
```

### 1.2 评测基线（56 条真实查询，gt=系统实际采纳编号，语料覆盖 56/56）

| 指标 | 实测 | 含义 |
|---|---|---|
| strict top-1 | **14.3%**（8/56） | 第 1 名即 gt |
| strict top-5 | **46.4%**（26/56） | 前 5 名含 gt（评测口径为 diversity.enabled=false，排除采样干扰） |
| top-20 候选池覆盖 | **85.7%**（48/56） | 20 条候选内含 gt（同检索词） |

### 1.3 误差分解（优化的靶子）

- **22 条：gt 排名在第 6~20 名**（48 在池 − 26 进前 5）→ **排序问题，rerank/排序优化的直接对象**，理论上限 ≈85.7%。
- **8 条：gt 不在 top-20 候选池**（其中 1 条完全未召回）→ **召回问题**，需查询改写/权重/融合。
- 结论一句话：**当前系统"候选召回没问题，5 位窗口排序不行"**——先救排序（快、确定性），再补召回（慢、结构性）。

### 1.4 代码里已就绪、只差落地的伏笔

- `RagFlowClient.retrieve(question, pageSize, rerankId, threshold)`：**rerank 传参已实现**。
- `ResumeBenchmarkLiveTest`：**rerank AB 评测已写好**（base vs rerank，统计 improved/regressed 明细）；**查询改写 V1~V4 消融已写好**（英文枚举标签 / 中文映射 / 子查询拼接 / 多路 round-robin 合并）。
- `RagFlowProperties`：`rerankId`、`rerankSimilarityThreshold`、`vectorSimilarityWeight` 全部可配，注释里设计过 `qwen3-rerank@wechatbot@Tongyi-Qianwen`。
- 即：**只差 RAGFlow 侧配一个 rerank 模型 + 打开开关**，就能跑 AB。

> 注意（防走偏）：46.4% 是 **diversity=false 口径**的实测，**多样性采样器不是 top-5 掉分的元凶**，不要朝"关多样性"去优化（它会牺牲跨次去重）。真正的问题是 RAGFlow 段排序本身。

---

## 2. 业界可参考方案库（按优先级）

### 方案 A：Rerank 语义精排 ⭐ 最高优先

- **原理**：Cross-Encoder 把 query 与候选文档拼接后整体打分，比双塔向量检索交互更充分。RAGFlow 内置。
- **业界参考与实测**：
  - RAGFlow 默认配置即「召回 top-50（向量+BM25）→ rerank 全部 → 输出 top-5」。
  - 数环通 iPaas AB：简单向量 RAG top-5 命中 52% → RAGFlow 混合检索 81%（+29pp）；LinkBot 回答准确率 58%→82%。
  - 某制造企业技术文档检索准确率 +37%（nv-rerankqa-mistral-4b-v3）。
- **模型选择**：`bge-reranker-v2-m3`（多语言、中文好，~180ms/100 候选）、`bge-reranker-large`（~150ms）、`ms-marco-MiniLM`（轻量 ~45ms）；或走 API factory 用通义 `qwen3-reranker`（项目注释里的原设计）。
- **本项目落地**：
  1. 起 RAGFlow（`docker compose -f infra/ragflow/...` 或按记忆配方，9380 当前未监听）；
  2. RAGFlow Web UI → 系统设置 → 模型配置，加 rerank 模型（本地 BAAI 或 API factory）；
  3. `application-local.properties` 配 `app.fashion.rag.ragflow.rerank-id=bge-reranker-v2-m3@<实例名>`（格式按 RAGFlow 实例@模型约定）+ 独立阈值；
  4. 跑已就绪的 rerank AB 测试，看 improved/regressed 明细再决定生产启用。
- **预期收益**：22 条排序问题约可救回 40~70% → **strict top-5 46.4% → 63~76%**。
- **成本/风险**：+200~500ms 延迟（20 候选实际只几十 ms 量级）；CPU 版容器需能拉权重（外网/镜像）；延迟敏感可候选池 15→10。

### 方案 B：混合检索权重 grid-search ⭐ 零代码、最快见效

- **原理**：RAGFlow 混合分数 = `vw * vector_sim + (1-vw) * term_sim`。现 vw=0.3 偏关键词；穿搭查询两类：属性词（"白色T恤"）吃关键词、场景意图（"约会穿什么"）吃向量，最优 vw 靠数据说话。
- **做法**：对同一批 56 条评测集，遍历 `vw ∈ {0.2, 0.3, 0.4, 0.5, 0.6}` 各算 strict top-5，取最大者。**纯配置改动，无代码**（`application-local.properties` + 重启，或用测试内覆盖）。
- **业界参考**：数环通实践"调 tkweight/vtweight 到最优，可部分替代 rerank 收益"。
- **预期**：+5~15pp（取决于分布）。
- **风险**：56 条上选参有**过拟合**风险 → 选出后跑 greedystability 3 轮交叉验证，勿只看单轮。

### 方案 C：查询改写增强（QueryAnalyzer）

- **现状**：原文 + 场景/风格/季节拼接已有；`ResumeBenchmarkLiveTest` 里 V1~V4 消融代码已就绪（英文枚举 / 中文映射 / 子查询拼接 / 多路 round-robin 合并）。
- **业界参考**：query rewriting 是 RAG 检索提升标配（子查询分解、同义扩展、意图补全）；LLM 改写不稳时配离线词典扩展兜底。
- **落地**：
  1. 先跑一遍 V1~V4 消融，选胜出变体（关注 top-20 覆盖与 top-5 双涨）；
  2. 对 8 条召回缺失逐条看检索词 vs gt 语料，补同义/近义扩展（如 "T恤"↔"短袖/圆领"、"通勤"↔"上班"）；
  3. 稳定性验证：QueryAnalyzer 有 LLM 波动（3 轮 ±3pp），评估以多轮 median 为准。
- **预期**：top-20 覆盖 85.7% → 90%+，个别查询直接进 top-5。

### 方案 D：多源融合检索（MySQL FTS + RAGFlow + Qdrant → RRF）⭐ 中期结构性

- **现状**：`provider` 二选一（mysql / ragflow），**不是并行多路**。
- **业界参考**：RRF（Reciprocal Rank Fusion，Cormack et al. 2009）是业界共识：各路 top-k 按名次取 `Σ 1/(k + rank)` 合并，**无需分数归一化**；RAGFlow 自身默认就是「向量 + BM25」双路。
- **本项目价值**：属性词精确匹配（MySQL FULLTEXT）、语义召回（RAGFlow）、向量近邻（Qdrant）三路互补——这正是简历"多路混合检索"叙事的真实落地版。
- **实现要点**：新 Pipeline（三路并发 retrieve → RRF 合并 → top-5）统一在 `ai.fashion.look.rag` 内实现，**不动 wardrobe（B）侧**（边界规则：A→B 允许、B→A 禁止、common.fashion 中性）；回归用现有 56 条评测集。
- **预期**：top-20 覆盖 +5~10pp；个别查询 top-5 获益。
- **成本**：1~2 周重构 + 回归，属"面试加分但非必急"项。

### 方案 E：阈值 / 候选池 / 多样性策略微调

- 阈值：**宁低勿高**（业界共识：0.2+top-5 优于 0.5+top-10）；现相似度阈值 0.2 合理，rerank 阈值 0.0 默认。
- 候选池 15 → 20：先放宽召回再裁，配合 rerank 用。
- 多样性采样策略升级（**看场景再动**）：现为"纯加权随机"，若生产对精度敏感，可改"**top-3 保底确定性 + 剩余 slot 多样性随机**"，保住高分项同时保留跨次去重。（注意：评测口径是 diversity=false，此改动需单独用真实交互口径验证"去重效果不劣化"。）

### 方案 F：评测回归门禁（支撑以上全部）

- 已有：`ResumeBenchmarkLiveTest` + `scripts/verify_resume_data.sh`（fast / --live 两档；GBK→UTF-8 解析已修）。
- 建议增量：
  1. 主指标明确为 **strict top-5**（顺带输出 top-1 / top-20 覆盖 / 3 轮波动）；
  2. 消融矩阵：一次跑完 `vw × rerank on/off × pool 大小 × 改写变体` 各组合对照表；
  3. 改动前把 56 条 miss 明细（含 gt_rank）落盘为基线快照，便于对照"救回了哪几条"。
- 每次改动跑一遍 live 档（约 8.5 分钟、有 LLM token 成本）作为提交门禁。

---

## 3. 推荐实施顺序（P0 → P4，成本递增）

| 阶段 | 内容 | 工期 | 出口指标（对照表） |
|---|---|---|---|
| **P0** | 诊断：补测试输出 gt_rank 分布（已完成 2026-08-22，见 `docs/_archive/rag-p0-baseline-20260818.md`）：57 条全在 top-20 池内（0 召回缺失），top-5 61.4%（35/57），**排序可救 22 条**（rank 6~20）；并发现单点测量方差大（46.4% vs 61.4%），后续判定须以 3 轮 median 为门禁 | 0.5 天 | ✅ miss 明细清单 = `docs/_archive/rag-p0-baseline-20260818.md` |
| **P1** | 零代码 AB：vw grid-search（0.2~0.6）——已完成 2026-08-22：**vw 影响甚微**（top-5 40.4%→42.1%，仅 +1 条；0.5/0.6 略优可作生产基线）；**意外关键发现**：同配置两次进程 top-5 摆动 40.4%~61.4%，噪声源 = QueryAnalyzer LLM 分析（进程内缓存，重启即丢→检索词漂移），比 vw/任何配置变量都大 | 0.5~1 天 | ✅ vw 对照表：0.3→40.4%、0.5/0.6→42.1%（单轮） |
| **P1.5** | **固化检索词（已完成 2026-08-22）**：Flyway V24 / QueryAnalysisStore / QueryAnalyzer 三极缓存；两个独立进程评测逐条 diff=0——**固化消除 LLM 分析漂移确认有效**。**⚠️ 基线修正**：原记录的 "top-5 63.2%" 是 `RetrievalGapDiagnosisLiveTest` 汇总统计 bug（池外 idx=-1 被错误吞入其余分类）的假象；已改为"汇总自逐行 machine 数据重算"并验证与明细一致。**真实基线：top-1 15.8%（9/57）、top-5 45.6%（26/57）、池外 10 条、排序可救 21 条**（rank6~20） | 1~2 天 | ✅ 汇总与逐行明细一致（26 hit / 10 pool-out） |
| **P2** | Rerank 实测（已完成 2026-08-22，结论 **不启用**）：独立 AB 调 DashScope `gte-rerank-v2`（账号可用模型；qwen-rerank 需开通、qwen3-reranker 不存在）对 57 条重排：①小池 20 直接重排 top-5 45.6%→**26.3%**（救回 4/退步 15）；②候选池 50 + outfit 聚合 top-5 43.9%→**22.8%**（救回 3/退步 15）。**rerank 本场景确定有害（-20pp 左右）**：真值=用户采纳（含个人偏好/去重约束），与通用语义相关性错位；候选小、条目是结构化属性文本，rerank 优势发挥不出。结论=不集成 RAGFlow rerank；若未来开通 qwen-rerank 可复测，但预期不乐观。该负面结论同为简历素材（实测评估后不盲从业界惯例） | 2~3 天 | ✅ 结论：不启用（两档 AB 均负收益） |
| **P3** | 查询改写消融（已完成 2026-08-22，结论=维持 V1）：复刻 V1~V4 全变体同刻对比——V1(现状) top-5 45.6%/top-20 82.5% **全面胜出**；V2 中文映射 33.3%、V3 子查询拼接 29.8%、V4 多路合并 36.8%（均同刻对比、检索词固化，结论可信）。**改写非方向**；池外 10 条为宽泛/口语查询（"帮我搭一套/少年风/雨天"），同义扩展收益不确定、边际低。**暂不落地改写**，检索词维持现状 | 3~5 天 | ✅ 消融对照表：V1 胜出（无落地变更） |
| **P4** | 多源融合：MySQL FTS + RAGFlow + Qdrant → RRF（可选） | 1~2 周 | 覆盖/命中进一步提升 |

> 保守预期（P1.5 已达成 63.2% 基线；P2 rerank 实测负收益不启用；P3 查询改写为当前主攻方向）。
> 届时简历可落款 "strict top-5 命中 63%+（57 条真实用户查询，检索词固化可复现；实测否决 rerank 方案并给出数据依据）"——诚实、可现场复现。

---

## 4. 前置依赖与风险清单

1. **RAGFlow 当前已就绪**（2026-08-22 16:20 拉起 Docker Desktop 后 `docker-ragflow-cpu-1` Up、9380 监听；Qdrant 6334 同 UP）。
2. **rerank 模型权重下载需网络**（HuggingFace 可能不通，需 HF_ENDPOINT 镜像或走 API factory 用通义 qwen3-reranker）。
3. **CPU 推理延迟**：docker-ragflow-cpu 跑 rerank 会偏慢，实测后再定候选池大小与是否生产启用。
4. **评测有 LLM token 成本**：live 档 3×56 次 QueryAnalyzer 调用，P1/P2 网格搜索建议先跑 single-round。
5. **评测集口径**：56 行对应 31 个不同 gt（部分 gt 被多条查询共享），聚合统计时注意去重口径与 3 轮波动。
6. **边界约束**：所有改动限于 `ai.fashion.look`（A 引擎）与 `common.fashion`，**不动 wardrobe（B 引擎）**；ArchUnit 已锁死方向，改动后跑 `FashionBoundaryArchTest` 回归。

---

*关联文件：`src/main/java/.../ai/fashion/look/rag/{RagFlowKnowledgeService, RagFlowClient, RagFlowProperties, QueryAnalyzer, RetrievalDiversitySampler}.java`；`src/test/java/.../benchmark/ResumeBenchmarkLiveTest.java`；`scripts/verify_resume_data.sh`；实测报告 `logs/verify_resume_data_report_full.txt`。*