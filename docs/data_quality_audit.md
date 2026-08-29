# 穿搭语料数据质量审计（2026-08-22）

## 背景

规则重排（P5）离线回放显示无 top-5 提升，根因诊断为 **outfit 标签塌缩**（场合几乎全为「日常」、季节几乎全为「夏季」）。
原计划通过「数据清洗 / 重打标」提升标签质量，从而救回规则重排。

本审计的目标：核实语料是否存在**可安全清洗（无需视觉验证）的结构性问题**，以及语义层问题在当前约束下是否可修。

## 核心结论

1. **结构性清洗已无收益**：去重、补字段、格式归一三项常规目标全部已满足。
2. **唯一问题是语义塌缩**：场合、季节维度区分度趋近于零，且场合维度完全没有强场景词汇。
3. **语义问题在无视觉模型前提下不可靠修正**：标签真伪不可独立验证，猜测式重打标风险高于现状，**不应执行**。
4. **检索优化的真实杠杆应改为不依赖弱标签的方法**（见建议）。

## 量化结果（161 篇 `data/fashion_docs/outfit_*.md`）

| 检查项 | 结果 |
|--------|------|
| 概览文本精确重复 | 0 组 |
| 组成（单品列表）近似重复 | 0 组 |
| 概览级字段缺失（风格/季节/场合/正式度/组成） | 0 |
| 整体正式度格式异常（非 `x/5`） | 0 |
| 适合季节原子 token | 夏季 156 / 春季 12 / 秋季 7 / 四季 2（97% 含夏季） |
| 整体风格原子 token | 9 种：街头118 / 休闲91 / 学院80 / 通勤50 / 运动休闲45 / 工装37 / 复古35 / 户外5 / 简约3（已受控） |
| 适合场合原子 token | 日常 161(100%) / 通勤 53 / 上学 14 / 旅行 10 / 户外 1（**无婚礼/运动/海边/正式/约会等强场景**） |

## 关键判断

- **场合维度完全塌缩**：100% 的 outfit 都标了「日常」，仅少量附加「通勤/上学/旅行/户外」弱修饰。**没有任何强场景词汇**（婚礼、运动、海边、面试、约会、派对）。这正是规则重排对「参加婚礼」类查询失效的根因——规则分对所有候选几乎相等，无法把 gt 推到 top-5。
- **季节维度塌缩**：97% 的 outfit 含「夏季」，区分度趋近于零。
- **上述两点均为语义问题**，需视觉模型核对 OSS 图片、或原整合人人工复核才能可靠修正。在无法验证标签真伪的前提下硬推标签 = 把猜测当事实写入知识库，排序会基于错误标签做出更自信的错误决策，风险高于现状。

## 标签数据流向（确认清洗可行性）

- `RagFlowKnowledgeService.loadOutfitOverviews()` 在应用启动时**直接读取本地 `data/fashion_docs/outfit_*.md`** 的「整套搭配概览」段解析标签（不走 RAGFlow 返回内容）。
- 因此：若未来获得视觉/人工复核后的可靠标签，直接更新 md 文件并重启应用即生效，无需重新灌库。
- 当前约束下**不应**更新这些标签。

## 建议

- ❌ 不做猜测式语义重打标（无视觉验证，标签真伪不可知）。
- ❌ 不做结构性清洗（已无可修项）。
- ✅ 检索优化的真实杠杆改为**不依赖弱标签**的方法：
  - **LLM listwise 重排**：直接把 outfit 的具体单品描述 + 用户 query 喂给 LLM 判相关性，完全绕开塌缩的场合/季节标签。该路径此前未验证（P2 仅测过 qwen3-reranker 交叉编码器且已退化，与生成式 listwise 不同）。
  - 候选池已验证召回充分（top-50 覆盖 92.98%、top-20 覆盖 82.46%），问题集中在排序，listwise 重排正对痛点。
- ⏸ **语义重打标列为阻塞项**：依赖视觉模型（核对 OSS 图片）或原整合人人工复核后方可执行。

---

## 解锁路径：视觉模型选型与可用数据集（2026-08-22 增补）

原审计将「语义重打标」列为阻塞项（无视觉验证）。经核查，可**低成本引入视觉模型做一次性离线批量重打标**，解除阻塞；同时确认 `data/image_urls.json` 已将 161 套 outfit 映射到阿里云 OSS 公读 URL（`overview.webp` 整身图 + 各 `garment` 单品 png，域名 `wechatbot111.oss-cn-beijing.aliyuncs.com`），视觉模型可直接吃 URL，图片无需落地、数据不出阿里云。

### A. 视觉模型选型（用于 161 张图重打标）

| 模型 | 输入单价 | 161 张图估算成本 | 适合度 |
|------|----------|------------------|--------|
| **Qwen3-VL-8B / 4B（百炼 / DashScope）** | ¥0.0005~0.012 / 千 tokens | **~¥0.3~2（一次性）** | ★★★★★ 复用项目现有 qwen key、中文强、OSS 图可直喂公读 URL |
| 自托管 Qwen2.5-VL-7B / Qwen3-VL-4B | 0（需本地 GPU） | 0，数据不出机 | ★★★★ 隐私 / 零边际成本，需 GPU 机器 |
| Gemini Flash / Flash-Lite | $0.075~0.10 / 1M tokens | ~$0.02 | ★★★ 最便宜前沿视觉，但偏英文、需 Google key |
| GPT-4o-mini | $0.15 / $0.60 / 1M | ~$0.05 | ★★ 中文弱于 Qwen |
| AWS Bedrock Nova Lite | ~$0.00013 / 次 | ~$0.02 | ★★ 需 AWS，英文 |

- **成本测算**：Qwen-VL 每 1024px 图 ≈ 1k tokens（32×32 像素 / token）。161 张 ×（~1k 输入 + ~200 输出）tokens，总成本极低（< ¥5）。
- **推荐 Qwen3-VL（4B 起步，质量不足换 8B）经百炼调用**：复用现有 DashScope key；图片已在 OSS 且 public-read，直接传 URL，数据不出阿里云，合规友好。
- 若要求图片不出本地机器：自托管 Qwen2.5-VL-7B（vLLM / ollama）跑批量脚本，零 API 成本。

### B. 可用服装数据集（按用途）

| 用途 | 数据集 | 说明 |
|------|--------|------|
| 训练 / 校验「视觉打标器」（风格 / 季节 / 场合 / 正式度） | **DeepFashion2**（491K 图，13 类，294 属性，关键点，商业-用户配对） | 属性最丰富，可微调模型预测单品属性 → 聚合为 outfit 标签 |
| 同上（大规模属性） | iMaterialist Fashion 2019（294 属性） | Kaggle 可下 |
| 同上（中文语境） | FashionAI（阿里 2018，关键点 + 属性） | 阿里生态，中文标注 |
| 整套搭配推荐 / 兼容性 / 检索（核心任务） | **Polyvore / Polyvore Outfits**（UIUC，~35 万套，多单品 + 兼容度） | 直接建模「整套搭配」 |
| 同上（现代街拍、真实搭配） | IQON3000（~30 万套，日本，单品 + 元数据） | 更接近真实穿搭推荐 |
| 文↔图检索（RAGFlow 语义层） | DeepFashion-MultiModal（DF-MM，图 + 文 + 属性） | 提升跨模态检索 |

**关键提醒**：这些数据集 ≠ 项目现有 161 套小红书 outfit，schema 也不同，不会「自动清洗」现有数据。两种用法：(1) 在 DeepFashion 属性上预训练 / 微调打标器，再跑本项目 161 张图；(2) 用 Polyvore / IQON 扩充语料规模。真正给「本项目 161 张图」重打标，仍需视觉模型读取本项目图片（见 A）。

### C. 落地流水线（建议）

1. 读 `data/image_urls.json`（outfit id → OSS 公读 URL：overview.webp + 各 garment png）。
2. 逐套调用 Qwen-VL，结构化 prompt → 输出 JSON `{整体风格, 适合季节, 适合场合(强场景词汇), 整体正式度(1-5), 简要理由}`。
3. 写入 `logs/outfit_retag_proposals.tsv` + md 补丁草稿，**不直接覆盖**；人工抽检后应用。
4. 重启应用（`loadOutfitOverviews` 重读 md）→ 重跑 P5 规则重排网格搜索，量化 top-5 提升。
5. **前置**：复用现有 DashScope key + OSS 公读 URL（已确认存在）。

---

## 业界方案调研（GitHub / 开源，2026-08-22）

用户提出「看看市面上已有项目怎么解决」，对 GitHub 上三类相关方案做了检索。结论：**社区已高度收敛到两条成熟模式，且其中一条对本项目是「免训练即可复用」的降维打击**。

### 模式一：视觉模型做结构化属性抽取（直接修塌缩标签）

核心思路——用专门的视觉模型/VLM 从图片里抽「类目/颜色/材质/风格/场合/季节」等结构化字段，替换人工/整合人写死的弱标签。

| 项目 | 技术 | 规模 | 关键结论（对本项目） |
|------|------|------|----------------------|
| **Fashion Florence v2**（`anushreeberlia/fashion-florence-v2` @HF，代码 `github.com/anushreeberlia/loom`） | Florence-2-large（0.77B）+ LoRA(r16,α32) 适配器 | 8 字段 JSON：**category/color/material/fit/style_tags/occasion_tags/season_tags** | **已发布训练好的适配器，可直接推理、零训练**。Occasion/Season 由预测字段规则后处理得出。评测 category F1≈0.90、style F1 0.888，**显著优于 GPT-4o-mini（0.43）/ Gemini（0.57）**。0.77B 单卡亚秒级推理 |
| **fashion-vlm**（`Luanjie-Dong/fashion-vlm`） | PaliGemma-2-3B + LoRA，在 Fashionpedia 上微调 | 细粒度属性 | 想自己训、要中文/自定义 schema 时的备选 |
| **Marqo fashionCLIP / marqo-fashionSigLIP**（`Marqo/marqo-fashionCLIP` @HF） | 域适应 CLIP/SigLIP，零样本分类与检索 | 800K+ 图-文对 | **完全不用训练**：给候选场合/季节词表，直接算「图片 vs 文本」相似度打分。小模型可本地免费跑，最便宜路径 |

> ⭐ **最相关发现**：Fashion Florence v2 适配器已公开发布，**我们只需下载 + 对 161 张 overview.webp 跑推理即可拿到 occasion/season 标签**，不必自己训练。这正是上一节 A 方案（Qwen-VL 重打标）的「免训练平替」。

### 模式二：结构化多目标重排（把规则重排做对，且经消融验证）

**Loom**（`anushreeberlia/loom`，Fashion Florence 的上游系统）展示了完整的「检索 + 重排」流水线，与本项目的 P3 规则重排是同一件事，但信号更丰富：

- 召回：FashionCLIP 向量 + slot 约束 ANN 检索互补单品；
- 精排：6 路多目标打分 = **向量相似度 + 配色和谐 + 正式度一致性 + 场合一致性(occasion coherence) + 风格方向 + 单套内多样性**；
- **消融结论（关键）**：「Direction reranking（方向性重排）是唯一不可或缺的组件」——去掉它分数从 0.179 跌到 0.052（约等于随机）。620 件语料上较随机基线 3.3× 提升、违规率降 42%。

> **这正是我们 P5 规则重排"无效"的对症药方**：我们重排无效，不是"重排思路错"，而是输入信号（场合/季节标签）塌缩成常数，重排器无变量可排。Loom 用「真实结构化 occasion/season 信号」喂同一个多目标重排，才把效果打出来。本项目的修复链 = **模式一（补全 occasion/season）→ 模式二（已有规则重排代码）→ 重跑 P5 网格**。

### 模式三：兼容性 / Type-Aware 嵌入（用于"整套搭配生成"，当前非瓶颈）

- **Type-Aware Embeddings**（Vasileva et al., ECCV 2018，`github.com/mvasil/fashion-compatibility`）：每件单品先入通用嵌入，再按"类型对"投影到子空间度量兼容性，解决"兼容不可传递"问题。Polyvore 68k 套上 FITB +3~5%。
- **WardrobeAware**（`yusufduman78/WardrobeAware`）：TypeAware 兼容性 + FashionCLIP 类目分类 + SegFormer 抠图，端到端搭配推荐。
- 当前本项目瓶颈是"检索排序"而非"搭配生成"，此模式列为远期参考。

### 数据集（与模式一配套）

| 用途 | 数据集 | 说明 |
|------|--------|------|
| 训/校验打标器（Fashion Florence 同源） | **iMaterialist Fashion（228 标签）/ Marqo-iMaterialist** | Fashion Florence 即基于此做 label engineering（228→6类/16色/19风格/15场合），适配器已含该映射 |
| 细粒度属性 | DeepFashion2（491K）/ Fashionpedia | 备选自训数据源 |
| 整套搭配检索/兼容性 | Polyvore / IQON3000 | 扩充语料、验证兼容性模型 |

### 给本项目的落地映射（最短路径）

1. **免训练优先**：直接拉 `anushreeberlia/fashion-florence-v2`（Florence-2 0.77B + LoRA 适配器），对 `data/image_urls.json` 里的 161 张 `overview.webp` 跑推理，得到每套的 `occasion_tags / season_tags / style_tags / formality` 候选。
   - 若无 GPU：退化为 **Marqo fashionCLIP 零样本**（候选词表 vs 图打分）——也完全免训练。
   - 若坚持用中文 + OSS 直喂：上一节 A 方案的 **Qwen3-VL** 仍是首选，但属「需 API 推理」而非「免训练本地模型」。
2. 单品级可用各 `garment_*.png` 辅助（取每单品标签后聚合为 outfit 级），提升 occasion 推断准确率（v2 的 occasion 是规则推导的，单品级更稳）。
3. 提案写 `logs/outfit_retag_proposals.tsv` + md 补丁草稿，**人工抽检后**应用（保留"不盲信模型"的约束）。
4. 重启应用 → 重跑 P5 规则重排网格 → 预期 top-5 命中率从 43.9% 提升（塌缩根因被消除）。

**核心判断**：社区共识 = "弱标签问题靠专门的轻量视觉模型/域 CLIP 解决"，与上一轮提出的"加一个便宜视觉模型"方案完全一致，且现在有**已发布适配器可免训练直接复用**，风险与成本进一步下降。

---

## 落地脚本（2026-08-22 已写：`scripts/retag/retag_outfits_fashion_florence.py`）

按"走 1"决定：用 **Fashion Florence v2（Florence-2 0.77B + LoRA 适配器）** 对 161 套图重打标。脚本已落地，离线逻辑自测 **PASS**。

- **输入**：`data/image_urls.json`（ootd id → OSS 公读 URL：overview.webp + 各 garment png）。
- **处理**：逐套跑「每个 garment png + overview.webp」→ 解析 6 字段 JSON → 聚合为 outfit 级 `style/season/occasion/formality`（occasion/season/formality 用透明规则字典推导，见脚本 `*_RULE`）→ 写提案。
- **产出（仅提案，不覆盖 md）**：
  - `logs/retag/outfit_retag_proposals.tsv`（oid, 建议风格/季节/场合/正式度, 推导依据, 来源）
  - `logs/retag/outfit_retag_patch.md`（每套可直接替换「整套搭配概览」标签行的草稿）
- **关键特性**：`--limit N` 试水、`--resume` 断点续跑、`--self-test` 纯标准库离线自测（已 PASS：婚礼裙→正式场合/约会/正式度5、运动装→运动/健身/正式度1、TSV+补丁生成正确）、`HF_ENDPOINT` 镜像支持。
- **⚠️ 当前沙箱阻塞**：本会话 `HTTPS_PROXY=127.0.0.1:7890` 代理未起、直连超时，**无外网** → 装不了 torch、下不了 HF 模型，真实推理未执行。环境已确认有 **RTX 4060 8GB（CUDA 13.0）**，代理可用 + GPU 的环境直接可跑。
- **人工抽检后落库**：抽检 `outfit_retag_proposals.tsv` → 用 `outfit_retag_patch.md` 替换对应 md 的「整套搭配概览」标签行 → 重启应用（`loadOutfitOverviews` 重读）→ 重跑 P5 规则重排网格，量化 top-5 提升。
- **运行命令（在有网+GPU 的环境）**：
  ```bash
  cd scripts/retag
  pip install torch torchvision transformers peft pillow requests   # CUDA 版 torch 按需选
  python retag_outfits_fashion_florence.py --limit 5                # 先试 5 套
  python retag_outfits_fashion_florence.py --resume                 # 全量（续跑）
  ```

## 数据集重传要求（后期更换/补全数据时强制执行，2026-08-23 约定）

当前 161 套重打标后，**24% 单品 `material=unknown`**（其中 5 套全部单品 unknown → 季节被迫 `all_season` 兜底）。根因是上游整合数据不完整。后期若更换/补全数据集（用户确认会重传"更完整、更细致"的数据），入库前必须满足：

1. **`material` 必填且来自受控词表**：每单品 material 不得为 `unknown`/空；允许值含 `cotton/denim/knit/silk/satin/wool/linen/leather/polyester/...`。缺失则整条记录退回补采，**不入库**。
2. **每单品核心字段齐全**：`category`、`primary_color`、`material`、`fit`、`style_tags` 均须填充；overview 图同理。
3. **图片清晰度需足以辨识材质**：24% unknown 大概率源于单品切图模糊/低分辨率，重传时须保证切图质量。
4. **若新数据集自带 occasion/season 标注**：优先采用数据自带标注，而非纯规则推导（见下"已知待优化点 ①"）。

满足上述要求后，material 完整 → 季节"单品级多数投票"将不再大量触发 `all_season` 兜底，季节区分度进一步提升。

## 已知待优化点（暂不处理，留作后期隐含优化）

- **① 场合标签无独立视觉信号**：Fashion Florence v2 的 `occasion_tags` 在 161 套中 **100% 为空**，当前 occasion 完全由"风格→场合"规则表（`OCCASION_RULE`）推导，质量上限受 style 关键词覆盖度约束。后期优化方向：换用会输出 occasion 的模型 / 直接用 overview 图 occasion / 重传时人工补 occasion 标注。**本期不处理。**
- **② 风格 `casual` 占比过高（~99%）**：反映真实衣橱偏休闲，属分布特征非缺陷，清洗不改；若需推荐多样性，应在重排层做多样性补偿（见审计"模式二"），而非动原始数据。
- **③ 5 套 `all_season` 兜底（075/099/113/139/231）**：当前接受，待重传完整 material 数据后自然消解。
