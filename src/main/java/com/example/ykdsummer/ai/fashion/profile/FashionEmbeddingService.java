package com.example.ykdsummer.ai.fashion.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 穿搭 Embedding 服务。
 *
 * <p>调用阿里云百炼（DashScope）OpenAI 兼容的 {@code /embeddings} 接口，
 * 将文本编码为向量，用于用户画像的相似度检索。
 *
 * <p>核心能力：
 * <ul>
 *   <li>{@link #embed} — 文本 → float[] 向量</li>
 *   <li>{@link #cosineSimilarity} — 两个向量的余弦相似度</li>
 *   <li>{@link #findTopKSimilar} — 从候选列表中找出与目标向量最相似的 Top-K</li>
 * </ul>
 *
 * <p>降级策略：Embedding API 调用失败时返回 null，上层回退到最近 N 条策略。
 */
@Component
public class FashionEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(FashionEmbeddingService.class);
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v3";
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String embeddingModel;

    public FashionEmbeddingService(
            @Value("${app.ai.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${app.ai.embedding.api-key:}") String apiKey,
            @Value("${app.ai.embedding-model:" + DEFAULT_EMBEDDING_MODEL + "}") String embeddingModel) {
        this.embeddingModel = embeddingModel != null && !embeddingModel.isBlank()
                ? embeddingModel : DEFAULT_EMBEDDING_MODEL;
        this.objectMapper = new ObjectMapper();
        this.restClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("FashionEmbeddingService initialized: model={}, baseUrl={}", this.embeddingModel, baseUrl);
    }

    /**
     * 将文本编码为向量。
     *
     * @param text 待编码文本
     * @return 向量数组；调用失败时返回 null
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String requestBody = objectMapper.writeValueAsString(new EmbeddingRequest(embeddingModel, text));

            String responseJson = restClient.post()
                    .uri("/embeddings")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return parseEmbeddingResponse(responseJson);
        } catch (Exception e) {
            log.warn("Embedding API call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 计算两个向量的余弦相似度。
     *
     * @return 相似度 [-1, 1]；任一为 null 或长度不匹配时返回 0
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0f;
        }
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0f : (float) (dot / denom);
    }

    /**
     * 从候选向量列表中找出与目标向量最相似的 Top-K。
     *
     * <p>对每个候选计算与 {@code query} 的余弦相似度，按降序取前 {@code k} 个。
     * 候选为 null 的位置自动跳过（相似度记为 0）。
     *
     * @param query      目标向量
     * @param candidates 候选向量列表（允许含 null 元素）
     * @param k          返回数量上限
     * @return 按相似度降序排列的 {@link ScoredIndex} 列表
     */
    public static List<ScoredIndex> findTopKSimilar(float[] query, List<float[]> candidates, int k) {
        if (query == null || candidates == null || candidates.isEmpty() || k <= 0) {
            return List.of();
        }
        List<ScoredIndex> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            float score = cosineSimilarity(query, candidates.get(i));
            scored.add(new ScoredIndex(i, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredIndex::score).reversed());

        List<ScoredIndex> result = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            result.add(scored.get(i));
        }
        return result;
    }

    /**
     * 将向量序列化为 JSON 字符串（用于存储到 MySQL）。
     */
    public String serialize(float[] embedding) {
        if (embedding == null) return "";
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception e) {
            log.warn("Failed to serialize embedding: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 从 JSON 字符串反序列化向量。
     */
    public float[] deserialize(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) return null;
            float[] result = new float[node.size()];
            for (int i = 0; i < node.size(); i++) {
                result[i] = (float) node.get(i).asDouble();
            }
            return result;
        } catch (Exception e) {
            log.debug("Failed to deserialize embedding: {}", e.getMessage());
            return null;
        }
    }

    // ========== 内部类和方法 ==========

    /** 候选向量在原列表中的索引及其与目标向量的相似度得分。 */
    public record ScoredIndex(int index, float score) {}

    private record EmbeddingRequest(String model, String input) {}

    private float[] parseEmbeddingResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                log.warn("Embedding response has no data array");
                return null;
            }
            JsonNode embeddingNode = data.get(0).path("embedding");
            if (!embeddingNode.isArray()) {
                log.warn("Embedding response has no embedding array");
                return null;
            }
            float[] result = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                result[i] = (float) embeddingNode.get(i).asDouble();
            }
            log.debug("Embedding parsed: {} dimensions", result.length);
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse embedding response: {}", e.getMessage());
            return null;
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }
}
