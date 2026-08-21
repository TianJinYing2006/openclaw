package com.example.ykdsummer.ai.fashion.look.rag;

import com.example.ykdsummer.ai.fashion.look.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.look.model.RetrievedChunk;
import com.example.ykdsummer.ai.fashion.look.model.SeedEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p>上下文增强：RAGFlow 返回的是文档分块，可能只含部分单品信息。
 * 启动时从 {@code data/fashion_docs/outfit_*.md} 加载每套穿搭的完整概览，
 * 在 {@link #formatContext} 中为每个 chunk 补充完整搭配信息，确保 Stylist
 * 能看到每套穿搭的全部单品（上衣+下装+鞋子），避免图文脱节。
 */
@Component
@ConditionalOnProperty(name = "app.fashion.rag.provider", havingValue = "ragflow")
public class RagFlowKnowledgeService implements FashionKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(RagFlowKnowledgeService.class);
    private static final String FASHION_DOCS_DIR = "data/fashion_docs";

    /** 匹配 markdown 文档中的"整套搭配概览"段落。 */
    private static final Pattern OVERVIEW_PATTERN = Pattern.compile(
            "## 整套搭配概览\\s*\\n(.*?)(?=\\n## |\\Z)", Pattern.DOTALL);

    private final RagFlowClient ragFlowClient;
    private final RagFlowProperties properties;
    private final FashionRagDiversityProperties diversity;

    /** outfit_id → 完整搭配概览文本（从 markdown 文档提取）。 */
    private volatile Map<String, String> outfitOverviews = Map.of();

    public RagFlowKnowledgeService(RagFlowClient ragFlowClient,
            RagFlowProperties properties, FashionRagDiversityProperties diversity) {
        this.ragFlowClient = ragFlowClient;
        this.properties = properties;
        this.diversity = diversity;
    }

    @PostConstruct
    public void loadOutfitOverviews() {
        Map<String, String> overviews = new HashMap<>();
        Path docsDir = Paths.get(FASHION_DOCS_DIR);
        if (!Files.isDirectory(docsDir)) {
            log.warn("Fashion docs directory not found: {} (outfit overview enrichment disabled)", FASHION_DOCS_DIR);
            return;
        }
        try (var stream = Files.list(docsDir)) {
            stream.filter(p -> p.getFileName().toString().matches("outfit_\\d+\\.md"))
                    .forEach(p -> {
                        String outfitId = extractOutfitIdFromFileName(p.getFileName().toString());
                        if (outfitId == null) return;
                        try {
                            String md = Files.readString(p);
                            Matcher m = OVERVIEW_PATTERN.matcher(md);
                            if (m.find()) {
                                overviews.put(outfitId, m.group(1).strip());
                            }
                        } catch (IOException e) {
                            log.warn("Failed to read outfit doc {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list fashion docs: {}", e.getMessage());
        }
        this.outfitOverviews = overviews;
        log.info("Loaded {} outfit overviews from {}", overviews.size(), FASHION_DOCS_DIR);
    }

    private static String extractOutfitIdFromFileName(String fileName) {
        // outfit_003.md → 003
        Matcher m = Pattern.compile("outfit_(\\d+)\\.md").matcher(fileName);
        return m.matches() ? m.group(1) : null;
    }

    @Override
    public List<RetrievedChunk> retrieve(AnalyzedQuery query) {
        return retrieveExcluding(query, Set.of());
    }

    @Override
    public List<RetrievedChunk> retrieveExcluding(AnalyzedQuery query, java.util.Collection<String> excludeIds) {
        if (query == null) {
            return List.of();
        }

        String searchQuestion = buildSearchQuestion(query);
        if (searchQuestion.isBlank()) {
            log.warn("Empty search question, returning empty results");
            return List.of();
        }

        // 多样性采样：先取更大候选池（相关性排序），再在池内加权随机挑 top-k
        int topK = properties.getTopK();
        int pageSize = diversity.isEnabled()
                ? Math.max(topK, Math.max(1, diversity.getCandidatePool()))
                : topK;
        List<RagFlowClient.RagFlowChunk> rawChunks = ragFlowClient.retrieve(searchQuestion, pageSize);

        List<RetrievedChunk> chunks = new ArrayList<>();
        for (RagFlowClient.RagFlowChunk raw : rawChunks) {
            SeedEntry entry = toSeedEntry(raw);
            double score = Math.min(1.0, Math.max(0.0, raw.similarity()));
            chunks.add(new RetrievedChunk(entry, score));
        }
        // 历史滑动窗口：采样前排除最近已推荐过的 outfit，避免跨次重复
        if (excludeIds != null && !excludeIds.isEmpty()) {
            chunks.removeIf(c -> c.entry().id() != null && excludeIds.contains(c.entry().id()));
        }
        if (diversity.isEnabled()) {
            chunks = RetrievalDiversitySampler.sample(chunks, topK, diversity.getMinScore());
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
     * <p>RAGFlow 返回的 content 是 Markdown 分块的原文，文档名在
     * {@code document_keyword}（如 outfit_002.md）。我们把 content 放入 summary，
     * 从文档名提取编号作为 ID。
     */
    private SeedEntry toSeedEntry(RagFlowClient.RagFlowChunk raw) {
        // retrieval 响应没有 document_name，文档名在 document_keyword
        String docName = raw.documentName() != null && !raw.documentName().isBlank()
                ? raw.documentName()
                : raw.documentKeyword();
        if (docName == null) {
            docName = "unknown";
        }
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
        // outfit_002.md → 002；兼容重名后的 outfit_002(1).md → 002
        int underscore = docName.lastIndexOf('_');
        int dot = docName.lastIndexOf('.');
        if (underscore >= 0 && dot > underscore) {
            String id = docName.substring(underscore + 1, dot);
            int paren = id.indexOf('(');
            return paren > 0 ? id.substring(0, paren) : id;
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
            String outfitId = chunk.entry().id();
            sb.append(i + 1).append(". ");

            // 前置 outfit 编号标记（如 [outfit_002]），供发图链路解析 top-1 参考穿搭
            sb.append("[outfit_").append(outfitId).append("] ");

            // 补充完整穿搭概览（RAGFlow chunk 可能只含部分单品信息）
            String overview = outfitOverviews.get(outfitId);
            if (overview != null && !overview.isBlank()) {
                sb.append("\n【完整搭配】\n").append(overview).append("\n");
            }

            // RAGFlow chunk 的 content 已经是格式化的 Markdown 文本
            // 作为单品详细信息附加在概览之后
            String content = chunk.entry().summary();
            if (content != null && !content.isBlank()) {
                sb.append("\n【单品详情】\n").append(content);
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
