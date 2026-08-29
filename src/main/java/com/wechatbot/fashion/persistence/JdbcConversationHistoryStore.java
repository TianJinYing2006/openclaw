package com.wechatbot.fashion.persistence;

import com.wechatbot.fashion.ai.model.ConversationMessage;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcConversationHistoryStore implements ConversationHistoryStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcConversationHistoryStore.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcConversationHistoryStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<ConversationMessage> load(String userId, int limit) {
        if (limit < 1 || userId == null || userId.isBlank()) return List.of();
        try {
            List<ConversationMessage> newestFirst = jdbc.query("""
                    SELECT m.role, m.content
                    FROM chat_messages m
                    JOIN chat_conversations c ON c.id = m.conversation_id
                    WHERE c.external_user_id = ?
                    ORDER BY m.id DESC
                    LIMIT ?
                    """, (rs, row) -> new ConversationMessage(
                    ConversationMessage.Role.valueOf(rs.getString("role")), rs.getString("content")), userId, limit);
            List<ConversationMessage> chronological = new ArrayList<>(newestFirst);
            java.util.Collections.reverse(chronological);
            return List.copyOf(chronological);
        } catch (RuntimeException exception) {
            log.warn("Could not load persisted conversation, user={}", anonymize(userId), exception);
            return List.of();
        }
    }

    @Override
    public void appendTurn(String userId, ConversationMessage userMessage, ConversationMessage assistantMessage) {
        if (userId == null || userId.isBlank() || userMessage == null || assistantMessage == null) return;
        try {
            transactions.executeWithoutResult(status -> {
                long conversationId = conversationId(userId, ManagedInstanceScope.parse(userId));
                jdbc.batchUpdate("INSERT INTO chat_messages(conversation_id, role, content, direction, message_kind) VALUES (?, ?, ?, ?, 'TEXT')", List.of(
                        new Object[]{conversationId, userMessage.role().name(), userMessage.text(), direction(userMessage)},
                        new Object[]{conversationId, assistantMessage.role().name(), assistantMessage.text(), direction(assistantMessage)}
                ));
            });
        } catch (RuntimeException exception) {
            log.warn("Could not persist conversation turn, user={}", anonymize(userId), exception);
        }
    }

    @Override
    public void clear(String userId) {
        if (userId == null || userId.isBlank()) return;
        try {
            jdbc.update("""
                    DELETE m FROM chat_messages m
                    JOIN chat_conversations c ON c.id = m.conversation_id
                    WHERE c.external_user_id = ?
                    """, userId);
        } catch (RuntimeException exception) {
            log.warn("Could not clear persisted conversation, user={}", anonymize(userId), exception);
        }
    }

    private long conversationId(String userId, ManagedInstanceScope scope) {
        Long platformUserId = scope.resolvePlatformUserId(jdbc);
        jdbc.update("""
                INSERT INTO app_users(external_user_id, platform_user_id, instance_id) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE last_seen_at = CURRENT_TIMESTAMP,
                    platform_user_id = COALESCE(VALUES(platform_user_id), platform_user_id),
                    instance_id = COALESCE(VALUES(instance_id), instance_id)
                """, userId, platformUserId, scope.instanceId());
        jdbc.update("""
                INSERT INTO chat_conversations(external_user_id, platform_user_id, instance_id) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE platform_user_id = COALESCE(VALUES(platform_user_id), platform_user_id),
                    instance_id = COALESCE(VALUES(instance_id), instance_id), updated_at = CURRENT_TIMESTAMP
                """, userId, platformUserId, scope.instanceId());
        Long id = jdbc.queryForObject("SELECT id FROM chat_conversations WHERE external_user_id = ?", Long.class, userId);
        if (id == null) throw new IllegalStateException("Conversation row was not created");
        return id;
    }

    private static String direction(ConversationMessage message) {
        return switch (message.role()) {
            case USER -> "INBOUND";
            case ASSISTANT -> "OUTBOUND";
        };
    }

    private static String anonymize(String userId) {
        return Integer.toHexString(userId.hashCode());
    }
}
