package com.example.ykdsummer.ai.fashion.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAGFlow HTTP API 客户端封装。
 *
 * <p>仅封装检索接口（{@code POST /api/v1/retrieval}），不涉及数据集管理或文档上传。
 * 数据集创建和文档导入通过独立脚本完成，运行时只需检索。
 *
 * <p>RAGFlow 的 retrieval 接口返回知识库中与问题最相关的文档分块（chunks），
 * 不经过 LLM 生成，结果纯净且延迟低。
 */
@Component
public class RagFlowClient {

    private static final Logger log = LoggerFactory.getLogger(RagFlowClient.class);

    private final RagFlowProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient httpClient;

    public RagFlowClient(RagFlowProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = properties.getTimeout();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());

        this.httpClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory)
                .build();
    }

    /**
     * 检索知识库，返回与问题最相关的文档分块。
     *
     * @param question    自然语言检索词
     * @return 检索到的分块列表，失败返回空列表
     */
    public List<RagFlowChunk> retrieve(String question) {
        return retrieve(question, properties.getTopK());
    }

    /**
     * 检索知识库，返回与问题最相关的文档分块。
     *
     * @param question  自然语言检索词
     * @param pageSize  每页条数（可传入比 top-k 更大的候选池，供多样性采样）
     * @return 检索到的分块列表，失败返回空列表
     */
    public List<RagFlowChunk> retrieve(String question, int pageSize) {
        if (!properties.isConfigured()) {
            log.warn("RAGFlow not fully configured, returning empty results");
            return List.of();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("dataset_ids", List.of(properties.getDatasetId()));
        body.put("page", 1);
        body.put("page_size", pageSize);
        body.put("similarity_threshold", properties.getSimilarityThreshold());
        body.put("vector_similarity_weight", properties.getVectorSimilarityWeight());

        try {
            RagFlowRetrievalResponse response = httpClient.post()
                    .uri("/api/v1/retrieval")
                    .body(body)
                    .retrieve()
                    .body(RagFlowRetrievalResponse.class);

            if (response == null || response.data() == null || response.data().chunks() == null) {
                log.warn("RAGFlow returned empty response for question: {}", truncate(question));
                return List.of();
            }

            List<RagFlowChunk> chunks = response.data().chunks();
            log.info("RAGFlow retrieved {} chunks for question: {}",
                    chunks.size(), truncate(question));
            return chunks;

        } catch (Exception e) {
            log.error("RAGFlow retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    // ── RAGFlow API 响应模型 ──

    /**
     * RAGFlow retrieval 接口响应。
     *
     * <pre>
     * {
     *   "code": 0,
     *   "data": {
     *     "chunks": [
     *       {
     *         "id": "xxx",
     *         "content": "文档分块文本",
     *         "document_id": "xxx",
     *         "document_name": "outfit_002.md",
     *         "dataset_id": "xxx",
     *         "similarity": 0.85,
     *         "vector_similarity": 0.92,
     *         "term_similarity": 0.78,
     *         "positions": [[...]]
     *       }
     *     ],
     *     "total": 5
     *   }
     * }
     * </pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RagFlowRetrievalResponse(int code, String message, RagFlowData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RagFlowData(List<RagFlowChunk> chunks, int total) {}

    /**
     * RAGFlow 返回的单个文档分块。
     *
     * <p>注意：retrieval 响应中不返回 {@code document_name}，文档名实际在
     * {@code document_keyword} 字段（值为文件名，如 outfit_075.md）。
     * JSON 字段均为 snake_case，用 {@link JsonNaming} 统一映射到驼峰属性。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RagFlowChunk(
            String id,
            String content,
            String documentId,
            String documentName,
            String documentKeyword,
            String datasetId,
            double similarity,
            double vectorSimilarity,
            double termSimilarity
    ) {}
}
