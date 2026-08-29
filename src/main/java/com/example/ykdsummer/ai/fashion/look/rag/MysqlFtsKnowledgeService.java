package com.example.ykdsummer.ai.fashion.look.rag;

import com.example.ykdsummer.ai.fashion.look.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.look.model.RetrievedChunk;
import com.example.ykdsummer.ai.fashion.look.model.SeedEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 MySQL FULLTEXT 的穿搭知识检索（降级实现）。
 *
 * <p>使用 ngram 分词的全文检索从种子数据中查找相关穿搭案例。
 * 当 RAGFlow 不可用时作为自动降级方案。
 *
 * <p>仅在 {@code app.fashion.rag.provider=mysql}（默认）时激活。
 */
@Component
@ConditionalOnProperty(name = "app.fashion.rag.provider", havingValue = "mysql", matchIfMissing = true)
public class MysqlFtsKnowledgeService implements FashionKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(MysqlFtsKnowledgeService.class);
    private static final int TOP_K = 5;

    private final JdbcTemplate jdbcTemplate;
    private final FashionRagDiversityProperties diversity;
    private final ObjectMapper objectMapper;

    public MysqlFtsKnowledgeService(JdbcTemplate jdbcTemplate, FashionRagDiversityProperties diversity) {
        this.jdbcTemplate = jdbcTemplate;
        this.diversity = diversity;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public List<RetrievedChunk> retrieve(AnalyzedQuery query) {
        return retrieveExcluding(query, java.util.Set.of());
    }

    @Override
    public List<RetrievedChunk> retrieveExcluding(AnalyzedQuery query, java.util.Collection<String> excludeIds) {
        if (query == null) {
            return List.of();
        }

        try {
            FtsPlan plan = buildFtsPlan(query);
            if (plan.matchQuery().isBlank() && plan.likeTerms().isEmpty()) {
                log.warn("Empty FULLTEXT query, returning empty results");
                return List.of();
            }

            log.info("FULLTEXT query: {} (like fallback: {})",
                    plan.matchQuery().isBlank() ? "<none>" : plan.matchQuery(),
                    plan.likeTerms());

            StringBuilder sql = new StringBuilder(
                    "SELECT id, content, source, summary, top, bottom, shoes, accessories, " +
                    "style, scene, season, color_scheme, " +
                    "MATCH(content, style, scene, season) AGAINST (? IN BOOLEAN MODE) AS relevance " +
                    "FROM fashion_seed_fts WHERE");

            List<Object> params = new ArrayList<>();
            if (!plan.matchQuery().isBlank()) {
                sql.append(" MATCH(content, style, scene, season) AGAINST (? IN BOOLEAN MODE)");
                // SELECT 中的 AGAINST 用于计算 relevance 排序分，WHERE 中的 AGAINST 用于过滤，
                // 两处占位符都需要绑定查询词
                params.add(plan.matchQuery());
                params.add(plan.matchQuery());
            } else {
                sql.append(" 1=0");
            }
            for (String like : plan.likeTerms()) {
                sql.append(" OR content LIKE ?");
                params.add("%" + like + "%");
            }
            // 多样性采样：先取更大候选池（按相关度），再在池内加权随机挑 top-k
            int poolSize = diversity.isEnabled()
                    ? Math.max(TOP_K, Math.max(1, diversity.getCandidatePool()))
                    : TOP_K;
            sql.append(" ORDER BY relevance DESC LIMIT ?");
            params.add(poolSize);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());

            List<RetrievedChunk> chunks = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                SeedEntry entry = mapToEntry(row);
                double score = Math.min(1.0, Math.max(0.0, getDouble(row, "relevance")));
                chunks.add(new RetrievedChunk(entry, score));
            }
            // 历史滑动窗口：采样前排除最近已推荐过的 outfit，避免跨次重复
            if (excludeIds != null && !excludeIds.isEmpty()) {
                chunks.removeIf(c -> c.entry().id() != null && excludeIds.contains(c.entry().id()));
            }
            if (diversity.isEnabled()) {
                chunks = RetrievalDiversitySampler.sample(chunks, TOP_K, diversity.getMinScore());
            }

            log.info("MySQL FTS retrieved {} chunks for query", chunks.size());
            return chunks;

        } catch (Exception e) {
            log.error("MySQL FTS retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    private record FtsPlan(String matchQuery, List<String> likeTerms) {}

    private FtsPlan buildFtsPlan(AnalyzedQuery query) {
        List<String> rawTerms = new ArrayList<>();

        if (query.decomposedQueries() != null) {
            for (String sub : query.decomposedQueries()) {
                if (sub != null && !sub.isBlank()) {
                    rawTerms.add(sub);
                }
            }
        }

        if (query.originalQuery() != null && !query.originalQuery().isBlank()) {
            rawTerms.add(query.originalQuery());
        }

        if (query.params() != null) {
            for (String scene : FashionTagMapper.mapScene(query.params().scene())) {
                rawTerms.add(scene);
            }
            for (String style : FashionTagMapper.mapStyle(query.params().styleHint())) {
                rawTerms.add(style);
            }
            for (String season : FashionTagMapper.mapSeason(query.params().season())) {
                rawTerms.add(season);
            }
        }

        List<String> matchTerms = new ArrayList<>();
        List<String> likeTerms = new ArrayList<>();
        for (String term : rawTerms) {
            classifyTerm(term, matchTerms, likeTerms);
        }

        return new FtsPlan(String.join(" ", matchTerms), likeTerms);
    }

    private void classifyTerm(String input, List<String> matchTerms, List<String> likeTerms) {
        String cleaned = input.replace("\"", "").trim();
        if (cleaned.isBlank()) return;

        if (cleaned.length() < 3) {
            likeTerms.add(cleaned);
            return;
        }
        matchTerms.add(cleaned);
    }

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

    @Override
    public String formatContext(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "暂无相关穿搭参考";
        }

        StringBuilder sb = new StringBuilder("## 穿搭知识参考\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            // 前置 outfit 编号标记（如 [outfit_002]），供发图链路解析 top-1 参考穿搭
            String outfitId = chunks.get(i).entry().id();
            if (outfitId != null && !outfitId.isBlank()) {
                sb.append("[outfit_").append(outfitId).append("] ");
            }
            sb.append(i + 1).append(". ").append(chunks.get(i).toPromptText()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
