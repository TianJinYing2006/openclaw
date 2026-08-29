package com.wechatbot.fashion.ai.fashion.look.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * RAGFlow 连接与检索配置。
 *
 * <p>通过 {@code app.fashion.rag.ragflow.*} 前缀注入，仅在
 * {@code app.fashion.rag.provider=ragflow} 时生效。
 */
@ConfigurationProperties(prefix = "app.fashion.rag.ragflow")
public class RagFlowProperties {

    /** RAGFlow API 地址，默认本机 9380 端口。 */
    private String baseUrl = "http://127.0.0.1:9380";

    /** RAGFlow API Key，在 Web UI 的系统设置中生成。 */
    private String apiKey = "";

    /** 目标知识库（Dataset）ID，创建数据集后获取。 */
    private String datasetId = "";

    /** 检索返回的 top-k 条数。 */
    private int topK = 5;

    /**
     * 向 RAGFlow 检索时请求的候选 chunk 数量。
     *
     * <p>由于一个 outfit 文档会被切分成多个 chunk，返回后需要按 outfit 聚合，
     * 因此请求池必须大于最终返回的 outfit 数量。默认 50，保证有足够 outfit 覆盖。</p>
     */
    private int retrievalPageSize = 50;

    /** 相似度阈值，低于此值的结果被过滤。 */
    private double similarityThreshold = 0.2;

    /** 向量相似度权重（0~1），剩余部分为关键词相似度权重。穿搭检索偏关键词匹配。 */
    private double vectorSimilarityWeight = 0.3;

    /**
     * Rerank 模型标识（如 qwen3-rerank@wechatbot@Tongyi-Qianwen），留空则不做 rerank。
     *
     * <p>开启后检索请求携带 {@code rerank_id}，RAGFlow 对候选池做语义精排。
     * rerank 分数量纲与向量相似度不同（实测多在 0~0.2 区间），因此单独用
     * {@link #rerankSimilarityThreshold}（默认 0）过滤，避免误伤。
     */
    private String rerankId = "";

    /** 开启 rerank 时使用的相似度阈值。 */
    private double rerankSimilarityThreshold = 0.0;

    /**
     * 是否启用基于结构化参数（scene/season/style/formality）的规则重排。
     *
     * <p>通用 rerank 模型在本场景下效果差，启用规则重排可利用 QueryAnalyzer 已提取的
     * 结构化信息对 outfit 候选池做低成本微调。默认关闭，可通过配置开启。</p>
     */
    private boolean ruleRerankEnabled = false;

    /** 规则重排中语义分的权重（0~1），剩余部分为规则分权重。 */
    private double ruleRerankSemanticWeight = 0.7;

    /**
     * 是否启用多路召回 + RRF 融合（Reciprocal Rank Fusion）。
     *
     * <p>开启后对每条 query 并行发起 3 路 RAGFlow 检索：
     * <ol>
     *   <li>主路：{@code buildSearchQuestion} 输出的 HyDE + 原话（语义最丰富）</li>
     *   <li>子查询路：{@code decomposedQueries} 拼接（中英混合 token，救 BM25 召回）</li>
     *   <li>中文映射路：scene/season/styleHint 翻译成中文后拼接
     *       （救 FORMAL_EVENT/OUTDOOR 这类英文枚举跟 outfit 文档无词命中场景）</li>
     * </ol>
     * 每路按 outfit 聚合后用 RRF 公式 {@code 1/(k+rank)} 融合排序。
     * 融合后每个 outfit 仍保留各路 max similarity 作 semanticScore，
     * 不影响 rule rerank 公式量纲。
     *
     * <p>历史背景：P3 实验 V4 用 round-robin 合并退步 15 条；
     * RRF 是信息检索标准做法（Cormack et al. 2009），按 rank 倒数加权而非轮询，
     * 高位候选不会被低位候选稀释。
     *
     * <p><b>2026-08-23 评测结论：默认关闭</b>。HyDE+RRF 在当前 naive chunk
     * 配置下三组实验均退步（baseline 21% → 10-16%，最优 31.58% → 14-24%）。
     * 根因是 chunk 粒度错位——HyDE 整套语义 vs chunk 单品级，语义距离天然远，
     * HyDE 描述里的通用词（"西装/礼服/连衣裙"）反而召回 noise。代码保留待
     * outfit 级 chunking 重做后复测。
     */
    private boolean multiRouteEnabled = false;

    /**
     * 是否启用 HyDE（假设性 outfit 文档扩展）作为检索词主锚点。
     *
     * <p>false（默认）时 {@code buildSearchQuestion} 走旧拼接逻辑
     * （原查询 + 场景/风格/季节）；true 时优先用 {@code hypotheticalOutfit}。
     *
     * <p><b>2026-08-23 评测结论：默认关闭</b>。HyDE 单路 vw=0.3/vw=0.7
     * 均退步（baseline 21% → 12-16%）。代码保留待 outfit 级 chunking 后复测。
     */
    private boolean hydeEnabled = false;

    /** RRF 常数 k，越大对 rank 差异越不敏感，默认 60（业界标准值）。 */
    private int multiRouteRrfK = 60;

    /** HTTP 请求超时。 */
    private Duration timeout = Duration.ofSeconds(15);

    // ── getters / setters ──

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public int getRetrievalPageSize() { return retrievalPageSize; }
    public void setRetrievalPageSize(int retrievalPageSize) { this.retrievalPageSize = retrievalPageSize; }

    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }

    public double getVectorSimilarityWeight() { return vectorSimilarityWeight; }
    public void setVectorSimilarityWeight(double vectorSimilarityWeight) { this.vectorSimilarityWeight = vectorSimilarityWeight; }

    public String getRerankId() { return rerankId; }
    public void setRerankId(String rerankId) { this.rerankId = rerankId; }

    public double getRerankSimilarityThreshold() { return rerankSimilarityThreshold; }
    public void setRerankSimilarityThreshold(double rerankSimilarityThreshold) { this.rerankSimilarityThreshold = rerankSimilarityThreshold; }

    public boolean isRuleRerankEnabled() { return ruleRerankEnabled; }
    public void setRuleRerankEnabled(boolean ruleRerankEnabled) { this.ruleRerankEnabled = ruleRerankEnabled; }

    public double getRuleRerankSemanticWeight() { return ruleRerankSemanticWeight; }
    public void setRuleRerankSemanticWeight(double ruleRerankSemanticWeight) { this.ruleRerankSemanticWeight = ruleRerankSemanticWeight; }

    public boolean isMultiRouteEnabled() { return multiRouteEnabled; }
    public void setMultiRouteEnabled(boolean multiRouteEnabled) { this.multiRouteEnabled = multiRouteEnabled; }

    public boolean isHydeEnabled() { return hydeEnabled; }
    public void setHydeEnabled(boolean hydeEnabled) { this.hydeEnabled = hydeEnabled; }

    public int getMultiRouteRrfK() { return multiRouteRrfK; }
    public void setMultiRouteRrfK(int multiRouteRrfK) { this.multiRouteRrfK = multiRouteRrfK; }

    public boolean isRerankEnabled() {
        return rerankId != null && !rerankId.isBlank();
    }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    /**
     * 检查是否已配置足够的最小参数。
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && datasetId != null && !datasetId.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }
}
