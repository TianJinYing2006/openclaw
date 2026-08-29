package com.wechatbot.fashion.ai.fashion.look.rag;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * {@link QueryAnalyzer} 分析结果的 MySQL 持久缓存。
 *
 * <p>目的：把「同一输入 → 分析结果」固化，跨进程复用。QueryAnalyzer 的
 * 进程内 Caffeine 缓存重启即失，LLM 每次重分析生成的场景/风格/季节会漂移，
 * 导致检索词不同、评测命中率大幅摆动（实测同配置 top-5 在 40%~61% 之间），
 * 淹没什么优化信号。持久化后：评测固定检索词消除噪声；生产同表述命中同分析
 * （行为稳定 + 省 token）。
 *
 * <p>表：{@code fashion_query_analysis_cache}（Flyway V24）。key 为
 * contextualInput（profileContext + 用户输入）的 sha256。
 *
 * <p>不影响 {@link QueryAnalyzer#analyzeUncached}（确定性验证仍真实调 LLM）。
 * 所有读写异常一律吞掉记 warn——缓存失败不得阻塞检索主链路。
 */
@Component
public class QueryAnalysisStore {

    private static final Logger log = LoggerFactory.getLogger(QueryAnalysisStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public QueryAnalysisStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 命中返回反序列化结果；未命中/异常返回 null（视为 miss）。 */
    public AnalyzedQuery get(String contextualInput) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT analyzed_json FROM fashion_query_analysis_cache WHERE input_hash = ?",
                    String.class, sha256(contextualInput));
            if (rows.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(rows.get(0), AnalyzedQuery.class);
        } catch (Exception e) {
            log.warn("QueryAnalysisStore read failed: {}", e.getMessage());
            return null;
        }
    }

    /** 写入/覆盖（不存在则插入，重复用 ON DUPLICATE KEY 刷新结果）。 */
    public void put(String contextualInput, AnalyzedQuery analyzed) {
        try {
            String json = objectMapper.writeValueAsString(analyzed);
            jdbc.update("""
                    INSERT INTO fashion_query_analysis_cache (input_hash, contextual_input, analyzed_json)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE analyzed_json = VALUES(analyzed_json), contextual_input = VALUES(contextual_input)
                    """, sha256(contextualInput), contextualInput, json);
        } catch (Exception e) {
            log.warn("QueryAnalysisStore write failed: {}", e.getMessage());
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}