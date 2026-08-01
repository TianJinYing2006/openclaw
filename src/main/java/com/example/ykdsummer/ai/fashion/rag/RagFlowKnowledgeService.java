package com.example.ykdsummer.ai.fashion.rag;

import com.example.ykdsummer.ai.fashion.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.model.RetrievedChunk;
import com.example.ykdsummer.ai.fashion.model.SeedEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 RAGFlow 的穿搭知识检索（主实现）。
 *
 * <p>通过 RAGFlow 的 {@code /api/v1/retrieval} 接口进行向量+关键词混合检索，
 * 替代 MySQL FULLTEXT。RAGFlow 负责文档解析、分块、Embedding 和索引，
 * Java 侧只需发送检索请求并转换结果。
 *
 * <p>仅在 {@code app.fashion.rag.provider=ragflow} 时激活。
 * RAGFlow 未完整配置时，自动降级返回空上下文（不阻断管道）。
 *
 * <p>检索词构建策略：RAGFlow 是语义检索，不需要像 MySQL FTS 那样拆词。
 * 直接用原始查询 + 场景/风格/季节参数拼成自然语言检索词。
 */
@Component
@ConditionalOnProperty(name = "app.fashion.rag.provider", havingValue = "ragflow")
public class RagFlowKnowledgeService implements FashionKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(RagFlowKnowledgeService.class);

    private final RagFlowClient ragFlowClient;

    public RagFlowKnowledgeService(RagFlowClient ragFlowClient) {
        this.ragFlowClient = ragFlowClient;
    }

    @Override
    public List<RetrievedChunk> retrieve(AnalyzedQuery query) {
        if (query == null) {
            return List.of();
        }

        String searchQuestion = buildSearchQuestion(query);
        if (searchQuestion.isBlank()) {
            log.warn("Empty search question, returning empty results");
            return List.of();
        }

        List<RagFlowClient.RagFlowChunk> rawChunks = ragFlowClient.retrieve(searchQuestion);

        List<RetrievedChunk> chunks = new ArrayList<>();
        for (RagFlowClient.RagFlowChunk raw : rawChunks) {
            SeedEntry entry = toSeedEntry(raw);
            double score = Math.min(1.0, Math.max(0.0, raw.similarity()));
            chunks.add(new RetrievedChunk(entry, score));
        }

        log.info("RAGFlow retrieved {} chunks for question: {}", chunks.size(), truncate(searchQuestion));
        return chunks;
    }

    /**
     * 将 AnalyzedQuery 转换为 RAGFlow 检索词。
     *
     * <p>RAGFlow 是语义检索，直接用自然语言拼接效果最好：
     * 原始查询 + 场景 + 风格 + 季节。
     */
    private String buildSearchQuestion(AnalyzedQuery query) {
        StringBuilder sb = new StringBuilder();

        if (query.originalQuery() != null && !query.originalQuery().isBlank()) {
            sb.append(query.originalQuery());
        }

        if (query.params() != null) {
            if (query.params().scene() != null && !query.params().scene().isBlank()) {
                sb.append(" 场景:").append(query.params().scene());
            }
            if (query.params().styleHint() != null && !query.params().styleHint().isBlank()) {
                sb.append(" 风格:").append(query.params().styleHint());
            }
            if (query.params().season() != null && !query.params().season().isBlank()) {
                sb.append(" 季节:").append(query.params().season());
            }
        }

        // 如果 originalQuery 为空但有 decomposedQueries，用它们兜底
        if (sb.isEmpty() && query.decomposedQueries() != null) {
            for (String sub : query.decomposedQueries()) {
                if (sub != null && !sub.isBlank()) {
                    sb.append(sub).append(" ");
                }
            }
        }

        return sb.toString().trim();
    }

    /**
     * 将 RAGFlow chunk 转换为 SeedEntry（复用现有模型）。
     *
     * <p>RAGFlow 返回的 content 是 Markdown 分块的原文，
     * document_name 包含编号信息（如 outfit_002.md）。
     * 我们把 content 放入 summary，从 document_name 提取 ID。
     */
    private SeedEntry toSeedEntry(RagFlowClient.RagFlowChunk raw) {
        String docName = raw.documentName() != null ? raw.documentName() : "unknown";
        // outfit_002.md → 002
        String id = extractIdFromDocName(docName);

        return new SeedEntry(
                id,
                "ragflow_chunk",
                docName,
                null,           // outfit — RAGFlow 返回的是文本块，不是结构化单品
                null,           // tags
                raw.content(),  // summary — RAGFlow chunk 的完整文本
                null,
                null,
                null
        );
    }

    private String extractIdFromDocName(String docName) {
        if (docName == null) return "unknown";
        // outfit_002.md → 002
        int underscore = docName.lastIndexOf('_');
        int dot = docName.lastIndexOf('.');
        if (underscore >= 0 && dot > underscore) {
            return docName.substring(underscore + 1, dot);
        }
        return docName;
    }

    @Override
    public String formatContext(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "暂无相关穿搭参考";
        }

        StringBuilder sb = new StringBuilder("## 穿搭知识参考\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append(i + 1).append(". ");

            // RAGFlow chunk 的 content 已经是格式化的 Markdown 文本
            // 直接使用，不做二次格式化
            String content = chunk.entry().summary();
            if (content != null && !content.isBlank()) {
                sb.append(content);
            } else {
                // 兜底：用 toPromptText
                sb.append(chunk.toPromptText());
            }
            sb.append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
