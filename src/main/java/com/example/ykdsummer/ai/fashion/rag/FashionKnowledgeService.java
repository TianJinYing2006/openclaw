package com.example.ykdsummer.ai.fashion.rag;

import com.example.ykdsummer.ai.fashion.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.model.RetrievedChunk;
import com.example.ykdsummer.ai.fashion.model.SeedEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 穿搭知识检索服务（RAG 层）。
 *
 * <p>使用 SQLite FTS5 全文检索从种子数据中查找相关穿搭案例。
 * 检索在 Agent 启动前统一执行一次，结果共享给所有 Agent。
 *
 * <p>检索策略：
 * <ul>
 *   <li>用 AnalyzedQuery 的 decomposedQueries 拼接为 FTS5 MATCH 查询</li>
 *   <li>用 params.scene/season 做精确过滤增强</li>
 *   <li>返回 top_k=5 条结果</li>
 * </ul>
 */
@Component
public class FashionKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(FashionKnowledgeService.class);
    private static final int TOP_K = 5;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FashionKnowledgeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 检索穿搭知识。
     *
     * @param query 查询分析结果
     * @return 检索到的穿搭案例列表，失败返回空列表
     */
    public List<RetrievedChunk> retrieve(AnalyzedQuery query) {
        if (query == null) {
            return List.of();
        }

        try {
            // 构建 FTS5 查询：用子查询 + 场景关键词拼接
            String ftsQuery = buildFtsQuery(query);
            if (ftsQuery.isBlank()) {
                log.warn("Empty FTS query, returning empty results");
                return List.of();
            }

            log.info("FTS5 query: {}", ftsQuery);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, content, source, summary, top, bottom, shoes, accessories, " +
                    "style, scene, season, color_scheme, rank FROM fashion_seed_fts " +
                    "WHERE fashion_seed_fts MATCH ? ORDER BY rank LIMIT ?",
                    ftsQuery, TOP_K
            );

            List<RetrievedChunk> chunks = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                SeedEntry entry = mapToEntry(row);
                double score = 1.0 / (1.0 + getDouble(row, "rank"));
                chunks.add(new RetrievedChunk(entry, score));
            }

            log.info("RAG retrieved {} chunks for query", chunks.size());
            return chunks;

        } catch (Exception e) {
            log.error("RAG retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 构建 FTS5 MATCH 查询字符串。
     * 将子查询和场景参数拼接为 OR 查询。
     */
    private String buildFtsQuery(AnalyzedQuery query) {
        List<String> terms = new ArrayList<>();

        // 添加子查询（分词后取关键词）
        if (query.decomposedQueries() != null) {
            for (String sub : query.decomposedQueries()) {
                if (sub != null && !sub.isBlank()) {
                    terms.add(escapeFts(sub));
                }
            }
        }

        // 添加场景参数
        if (query.params() != null) {
            if (query.params().scene() != null && !query.params().scene().isBlank()) {
                terms.add(escapeFts(query.params().scene()));
            }
            if (query.params().season() != null && !query.params().season().isBlank()) {
                terms.add(escapeFts(query.params().season()));
            }
            if (query.params().styleHint() != null && !query.params().styleHint().isBlank()) {
                terms.add(escapeFts(query.params().styleHint()));
            }
        }

        // 添加原始查询
        if (query.originalQuery() != null && !query.originalQuery().isBlank()) {
            terms.add(escapeFts(query.originalQuery()));
        }

        if (terms.isEmpty()) return "";

        // FTS5 OR 查询
        return String.join(" OR ", terms);
    }

    /**
     * 转义 FTS5 特殊字符，将中文文本分词。
     */
    private String escapeFts(String input) {
        // FTS5 中双引号包裹整个短语作为精确匹配
        String cleaned = input.replace("\"", "");
        if (cleaned.isBlank()) return "";
        return "\"" + cleaned.trim() + "\"";
    }

    /**
     * 将数据库行映射为 SeedEntry。
     */
    private SeedEntry mapToEntry(Map<String, Object> row) {
        SeedEntry.Outfit outfit = new SeedEntry.Outfit(
                getStr(row, "top"), getStr(row, "bottom"),
                getStr(row, "shoes"), getStr(row, "accessories")
        );

        List<String> styles = parseList(getStr(row, "style"));
        List<String> scenes = parseList(getStr(row, "scene"));
        List<String> seasons = parseList(getStr(row, "season"));

        SeedEntry.Tags tags = new SeedEntry.Tags(
                styles, scenes, seasons, List.of(), getStr(row, "color_scheme")
        );

        return new SeedEntry(
                getStr(row, "id"), "blogger_look", getStr(row, "source"),
                outfit, tags, getStr(row, "summary"),
                null, null, null
        );
    }

    private List<String> parseList(String spaceSeparated) {
        if (spaceSeparated == null || spaceSeparated.isBlank()) {
            return List.of();
        }
        return List.of(spaceSeparated.split("\\s+"));
    }

    private static String getStr(Map<String, Object> row, String key) {
        Object val = row.get(key);
        return val == null ? "" : val.toString();
    }

    private static double getDouble(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val instanceof Number num) return num.doubleValue();
        return 0.0;
    }

    /**
     * 将检索结果格式化为 Agent prompt 可用的文本。
     */
    public String formatContext(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "暂无相关穿搭参考";
        }

        StringBuilder sb = new StringBuilder("## 穿搭知识参考\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            sb.append(i + 1).append(". ").append(chunks.get(i).toPromptText()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
