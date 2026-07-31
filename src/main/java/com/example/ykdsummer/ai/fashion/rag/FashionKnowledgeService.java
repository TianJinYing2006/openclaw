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
            // 构建 FTS5 查询：MATCH 长词（含英文枚举）+ LIKE 兜底短中文词
            FtsPlan plan = buildFtsPlan(query);
            if (plan.matchQuery().isBlank() && plan.likeTerms().isEmpty()) {
                log.warn("Empty FTS query, returning empty results");
                return List.of();
            }

            log.info("FTS5 query: {} (like fallback: {})",
                    plan.matchQuery().isBlank() ? "<none>" : plan.matchQuery(),
                    plan.likeTerms());

            StringBuilder sql = new StringBuilder(
                    "SELECT id, content, source, summary, top, bottom, shoes, accessories, " +
                    "style, scene, season, color_scheme, " +
                    "bm25(fashion_seed_fts, 1, 1, 1, 1, 1, 1, 1, 1, 8, 8, 8, 1) AS rank " +
                    "FROM fashion_seed_fts WHERE");

            List<Object> params = new ArrayList<>();
            if (!plan.matchQuery().isBlank()) {
                sql.append(" fashion_seed_fts MATCH ?");
                params.add(plan.matchQuery());
            } else {
                sql.append(" 1=0");
            }
            // trigram 无法匹配 1-2 字符短词，用 content LIKE 补充召回
            for (String like : plan.likeTerms()) {
                sql.append(" OR content LIKE ?");
                params.add("%" + like + "%");
            }
            sql.append(" ORDER BY rank LIMIT ?");
            params.add(TOP_K);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());

            List<RetrievedChunk> chunks = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                SeedEntry entry = mapToEntry(row);
                // bm25 返回负值，越相关越负；取绝对值避免除零，转为 0~1 分数
                double score = 1.0 / (1.0 + Math.abs(getDouble(row, "rank")));
                chunks.add(new RetrievedChunk(entry, score));
            }

            log.info("RAG retrieved {} chunks for query", chunks.size());
            return chunks;

        } catch (Exception e) {
            log.error("RAG retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** FTS 检索计划：MATCH 查询串 + LIKE 兜底短词列表。 */
    private record FtsPlan(String matchQuery, List<String> likeTerms) {}

    /**
     * 构建 FTS 检索计划。
     *
     * <p>双路召回：
     * <ul>
     *   <li>长词（≥3字符，含中英混合）→ MATCH 查询：中文短语加引号、英文枚举做 token 匹配</li>
     *   <li>短词（1-2 字符中文/英文）→ trigram 无法索引，转 content LIKE 兜底</li>
     * </ul>
     * 场景/风格/季节枚举经 {@link FashionTagMapper} 映射后因 bm25 列权重
     * （style/scene/season 为 8）排在最前。
     */
    private FtsPlan buildFtsPlan(AnalyzedQuery query) {
        List<String> rawTerms = new ArrayList<>();

        // 中文子查询（分词后取关键词）
        if (query.decomposedQueries() != null) {
            for (String sub : query.decomposedQueries()) {
                if (sub != null && !sub.isBlank()) {
                    rawTerms.add(sub);
                }
            }
        }

        // 原始查询短语
        if (query.originalQuery() != null && !query.originalQuery().isBlank()) {
            rawTerms.add(query.originalQuery());
        }

        if (query.params() != null) {
            // LLM 场景 → 数据枚举 token
            for (String scene : FashionTagMapper.mapScene(query.params().scene())) {
                rawTerms.add(scene);
            }
            // 风格提示 → 数据枚举 token
            for (String style : FashionTagMapper.mapStyle(query.params().styleHint())) {
                rawTerms.add(style);
            }
            // 季节 → 数据枚举 token
            for (String season : FashionTagMapper.mapSeason(query.params().season())) {
                rawTerms.add(season);
            }
        }

        List<String> matchTerms = new ArrayList<>();
        List<String> likeTerms = new ArrayList<>();
        for (String term : rawTerms) {
            classifyTerm(term, matchTerms, likeTerms);
        }

        return new FtsPlan(String.join(" OR ", matchTerms), likeTerms);
    }

    /**
     * 将单个检索词分类：长词进 MATCH，短词（<3 字符）进 LIKE 兜底。
     */
    private void classifyTerm(String input, List<String> matchTerms, List<String> likeTerms) {
        String cleaned = input.replace("\"", "").trim();
        if (cleaned.isBlank()) return;

        // trigram 索引无法有效匹配 1-2 字符词，走 LIKE 补充召回
        if (cleaned.length() < 3) {
            likeTerms.add(cleaned);
            return;
        }

        boolean hasCjk = cleaned.chars().anyMatch(c -> c >= 0x4E00);
        // 含中文或空格的短语加引号做精确匹配；纯 ASCII 单词（枚举）做 token 匹配
        if (hasCjk || cleaned.contains(" ")) {
            matchTerms.add("\"" + cleaned + "\"");
        } else {
            matchTerms.add(cleaned);
        }
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
