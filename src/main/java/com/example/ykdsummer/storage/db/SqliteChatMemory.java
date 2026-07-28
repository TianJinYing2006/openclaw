package com.example.ykdsummer.storage.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import com.example.ykdsummer.ai.model.ConversationMessage;
import org.springframework.ai.chat.messages.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * SQLite 实现�?{@link ChatMemory}，将会话消息持久化到本地数据库�?
 *
 * <p>conversationId 格式�?{@code "{userId}::{chatType}"}�?
 * 例如 {@code "wx_user_abc::single"}�?/p>
 *
 * <p>首次写入时会自动创建会话记录，无需预先初始化�?/p>
 */
@Component
public class SqliteChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(SqliteChatMemory.class);

    private static final String SEPARATOR = "::";

    private final JdbcTemplate jdbc;

    public SqliteChatMemory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ========== ChatMemory 接口实现 ==========

    @Override
    public void add(String conversationId, List<Message> messages) {
        String[] parts = parseConversationId(conversationId);
        String userId = parts[0];
        String chatType = parts[1];

        // 自动创建会话
        ensureSession(userId, chatType);

        // 批量插入消息
        for (Message message : messages) {
            jdbc.update("""
                    INSERT INTO chat_messages (user_id, chat_type, role, content)
                    VALUES (?, ?, ?, ?)
                    """,
                    userId, chatType,
                    toRoleString(message.getMessageType()),
                    message.getText()
            );
        }

        // 更新会话统计
        jdbc.update("""
                UPDATE chat_sessions
                SET msg_count = msg_count + ?, updated_at = datetime('now','localtime')
                WHERE user_id = ? AND chat_type = ?
                """, messages.size(), userId, chatType);
    }

    @Override
    public List<Message> get(String conversationId) {
        String[] parts = parseConversationId(conversationId);
        String userId = parts[0];
        String chatType = parts[1];

        return jdbc.query("""
                SELECT id, role, content FROM chat_messages
                WHERE user_id = ? AND chat_type = ?
                ORDER BY created_at ASC
                """,
                (rs, rowNum) -> toMessage(rs),
                userId, chatType
        );
    }

    @Override
    public void clear(String conversationId) {
        String[] parts = parseConversationId(conversationId);
        String userId = parts[0];
        String chatType = parts[1];

        jdbc.update("DELETE FROM chat_messages WHERE user_id = ? AND chat_type = ?",
                userId, chatType);
        jdbc.update("""
                UPDATE chat_sessions
                SET msg_count = 0, updated_at = datetime('now','localtime')
                WHERE user_id = ? AND chat_type = ?
                """, userId, chatType);
    }

    // ========== 扩展方法 ==========

    /** 获取指定会话的上下文窗口大小；不存在时返回默认�?*/
    public int getWindowSize(String conversationId, int defaultSize) {
        String[] parts = parseConversationId(conversationId);
        try {
            Integer size = jdbc.queryForObject("""
                    SELECT window_size FROM chat_sessions
                    WHERE user_id = ? AND chat_type = ?
                    """,
                    Integer.class, parts[0], parts[1]
            );
            return size != null ? size : defaultSize;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return defaultSize;
        }
    }

    /** 获取最�?N 条消息（按时间正序）。比 ChatMemory.get() 更节省内�?*/
    public List<Message> getLastN(String conversationId, int lastN) {
        String[] parts = parseConversationId(conversationId);
        return jdbc.query("""
                SELECT role, content FROM (
                    SELECT id, role, content FROM chat_messages
                    WHERE user_id = ? AND chat_type = ?
                    ORDER BY id DESC LIMIT ?
                ) sub ORDER BY id ASC
                """,
                (rs, rowNum) -> toMessage(rs),
                parts[0], parts[1], lastN
        );
    }

    // ========== 扩展方法（ConversationMessage 转换�?==========

    /**
     * 获取最�?N 条消息并转换�?{@link ConversationMessage} 列表�?
     * �?{@link com.example.ykdsummer.ai.service.AiChatService} 传递给网关调用�?
     */
    public java.util.List<ConversationMessage> getAsConversationMessages(String conversationId, int lastN) {
        return getLastN(conversationId, lastN).stream()
                .map(SqliteChatMemory::toConversationMessage)
                .toList();
    }

    static ConversationMessage toConversationMessage(Message msg) {
        return switch (msg.getMessageType()) {
            case USER -> new ConversationMessage(ConversationMessage.Role.USER, msg.getText());
            case ASSISTANT -> new ConversationMessage(ConversationMessage.Role.ASSISTANT, msg.getText());
            default -> new ConversationMessage(ConversationMessage.Role.USER, msg.getText());
        };
    }

    // ========== 内部方法 ==========

    private void ensureSession(String userId, String chatType) {
        jdbc.update("""
                INSERT OR IGNORE INTO chat_sessions (user_id, chat_type)
                VALUES (?, ?)
                """, userId, chatType);
    }

    static String[] parseConversationId(String conversationId) {
        int sep = conversationId.indexOf(SEPARATOR);
        if (sep <= 0 || sep >= conversationId.length() - SEPARATOR.length()) {
            log.warn("Invalid conversationId format: {}, expected userId::chatType", conversationId);
            return new String[]{conversationId, "single"};
        }
        return new String[]{conversationId.substring(0, sep), conversationId.substring(sep + SEPARATOR.length())};
    }

    static String toRoleString(MessageType type) {
        return switch (type) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            default -> "user";
        };
    }

    static MessageType toMessageType(String role) {
        return switch (role) {
            case "user" -> MessageType.USER;
            case "assistant" -> MessageType.ASSISTANT;
            case "system" -> MessageType.SYSTEM;
            default -> MessageType.USER;
        };
    }

    static Message toMessage(ResultSet rs) throws SQLException {
        String role = rs.getString("role");
        String content = rs.getString("content");
        return switch (role) {
            case "user" -> new UserMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "system" -> new SystemMessage(content);
            default -> new UserMessage(content);
        };
    }
}
