package com.example.ykdsummer.ai.fashion.profile;

import com.example.ykdsummer.ai.fashion.model.FashionConversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.List;

/**
 * 穿搭对话存储服务。
 *
 * <p>负责将穿搭相关对话写入 {@code fashion_conversations} 表，
 * 作为后续用户画像提取的数据源。
 *
 * <p>三段式写入策略：
 * <ol>
 *   <li>{@link #saveInitial} — 管道开始时写入用户输入 + 场景参数</li>
 *   <li>{@link #updateRecommendation} — 管道结束时回填推荐方案摘要</li>
 *   <li>{@link #updateFeedback} — 用户反馈时回填反馈内容</li>
 * </ol>
 *
 * <p>所有写操作均吞异常，保证数据库故障不影响穿搭主管道。
 */
@Component
public class FashionConversationService {

    private static final Logger log = LoggerFactory.getLogger(FashionConversationService.class);

    private final JdbcTemplate jdbc;

    public FashionConversationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入穿搭对话初始记录（管道开始时调用）。
     *
     * @param userId     用户 ID
     * @param userInput  用户原始穿搭需求
     * @param scene      QueryAnalyzer 提取的场景
     * @param season     季节
     * @param formality  正式度
     * @return 新记录的 ID；写入失败时返回 null
     */
    public Long saveInitial(String userId, String userInput,
                            String scene, String season, int formality) {
        try {
            String sql = """
                    INSERT INTO fashion_conversations
                        (user_id, user_input, scene, season, formality)
                    VALUES (?, ?, ?, ?, ?)
                    """;
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(conn -> {
                var ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, userId);
                ps.setString(2, userInput);
                ps.setString(3, scene != null ? scene : "");
                ps.setString(4, season != null ? season : "");
                ps.setInt(5, formality);
                return ps;
            }, keyHolder);

            Long id = keyHolder.getKey() != null ? keyHolder.getKey().longValue() : null;
            log.debug("Saved fashion conversation: id={}, user={}, scene={}", id, userId, scene);
            return id;
        } catch (Exception e) {
            log.warn("Failed to save fashion conversation: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 回填推荐方案摘要（管道结束时调用）。
     *
     * @param conversationId 对话记录 ID
     * @param recommendation  推荐方案摘要文本
     */
    public void updateRecommendation(Long conversationId, String recommendation) {
        if (conversationId == null) return;
        try {
            jdbc.update("""
                    UPDATE fashion_conversations
                    SET recommendation = ?
                    WHERE id = ?
                    """, recommendation != null ? recommendation : "", conversationId);
        } catch (Exception e) {
            log.warn("Failed to update recommendation for conversation {}: {}",
                    conversationId, e.getMessage());
        }
    }

    /**
     * 回填用户反馈（用户对推荐结果表态时调用）。
     *
     * @param conversationId 对话记录 ID
     * @param feedback        用户反馈内容，如 "喜欢方案2" / "不喜欢裙子"
     */
    public void updateFeedback(Long conversationId, String feedback) {
        if (conversationId == null || feedback == null || feedback.isBlank()) return;
        try {
            jdbc.update("""
                    UPDATE fashion_conversations
                    SET user_feedback = ?
                    WHERE id = ?
                    """, feedback, conversationId);
            log.info("Updated feedback for conversation {}: {}", conversationId, feedback);
        } catch (Exception e) {
            log.warn("Failed to update feedback for conversation {}: {}",
                    conversationId, e.getMessage());
        }
    }

    /**
     * 回填对话向量（管道结束后异步调用）。
     *
     * @param conversationId 对话记录 ID
     * @param embeddingJson  向量的 JSON 字符串
     */
    public void updateEmbedding(Long conversationId, String embeddingJson) {
        if (conversationId == null || embeddingJson == null || embeddingJson.isBlank()) return;
        try {
            jdbc.update("""
                    UPDATE fashion_conversations
                    SET embedding = ?
                    WHERE id = ?
                    """, embeddingJson, conversationId);
            log.debug("Updated embedding for conversation {}", conversationId);
        } catch (Exception e) {
            log.warn("Failed to update embedding for conversation {}: {}",
                    conversationId, e.getMessage());
        }
    }

    /**
     * 查询用户最近的穿搭对话记录（用于偏好提取）。
     *
     * @param userId 用户 ID
     * @param limit  最多返回的条数
     * @return 按时间正序排列的穿搭对话列表；无数据时返回空列表
     */
    public List<FashionConversation> findRecent(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            return jdbc.query("""
                    SELECT id, user_id, user_input, scene, season, formality,
                           recommendation, user_feedback, embedding, created_at
                    FROM (
                        SELECT * FROM fashion_conversations
                        WHERE user_id = ?
                        ORDER BY id DESC LIMIT ?
                    ) sub ORDER BY id ASC
                    """,
                    (rs, rowNum) -> new FashionConversation(
                            rs.getLong("id"),
                            rs.getString("user_id"),
                            rs.getString("user_input"),
                            rs.getString("scene"),
                            rs.getString("season"),
                            rs.getInt("formality"),
                            rs.getString("recommendation"),
                            rs.getString("user_feedback"),
                            rs.getString("embedding"),
                            rs.getString("created_at")
                    ),
                    userId, limit
            );
        } catch (Exception e) {
            log.warn("Failed to find recent fashion conversations for user {}: {}",
                    userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取用户最近一条穿搭对话（用于反馈检测）。
     *
     * @param userId 用户 ID
     * @return 最近一条穿搭对话；无数据时返回 null
     */
    public FashionConversation findLatest(String userId) {
        List<FashionConversation> list = findRecent(userId, 1);
        return list.isEmpty() ? null : list.get(0);
    }
}
