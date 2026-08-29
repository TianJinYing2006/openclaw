package com.example.ykdsummer.ai.fashion.look.rag;

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

    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }

    public double getVectorSimilarityWeight() { return vectorSimilarityWeight; }
    public void setVectorSimilarityWeight(double vectorSimilarityWeight) { this.vectorSimilarityWeight = vectorSimilarityWeight; }

    public String getRerankId() { return rerankId; }
    public void setRerankId(String rerankId) { this.rerankId = rerankId; }

    public double getRerankSimilarityThreshold() { return rerankSimilarityThreshold; }
    public void setRerankSimilarityThreshold(double rerankSimilarityThreshold) { this.rerankSimilarityThreshold = rerankSimilarityThreshold; }

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
