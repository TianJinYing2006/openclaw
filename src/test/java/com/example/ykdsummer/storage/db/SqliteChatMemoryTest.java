package com.example.ykdsummer.storage.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link SqliteChatMemory} 的单元测试。
 * 使用内存 SQLite 数据库，每次测试自动重建表结构。
 */
class SqliteChatMemoryTest {

    private static final String CONVERSATION_ID = "user_001::single";
    private static final String USER_ID = "user_001";
    private static final String CHAT_TYPE = "single";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private SqliteChatMemory memory;

    private SqliteChatMemory createMemory() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setPoolName("test-sqlite");
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);

        // 建表 DDL（与 DatabaseConfig.DatabaseInitializer 一致）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_sessions (
                    user_id      TEXT    NOT NULL,
                    chat_type    TEXT    NOT NULL DEFAULT 'single'
                                         CHECK(chat_type IN ('single', 'group')),
                    nick_name    TEXT    DEFAULT '',
                    status       TEXT    NOT NULL DEFAULT 'active'
                                         CHECK(status IN ('active', 'archived')),
                    msg_count    INTEGER NOT NULL DEFAULT 0,
                    window_size  INTEGER NOT NULL DEFAULT 20,
                    created_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
                    updated_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
                    PRIMARY KEY (user_id, chat_type)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id         TEXT    NOT NULL,
                    chat_type       TEXT    NOT NULL DEFAULT 'single',
                    role            TEXT    NOT NULL CHECK(role IN ('user','assistant','system')),
                    content         TEXT    NOT NULL,
                    msg_type        TEXT    DEFAULT 'text',
                    wx_msg_id       TEXT    DEFAULT '',
                    token_count     INTEGER DEFAULT 0,
                    created_at      TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
                    FOREIGN KEY (user_id, chat_type) REFERENCES chat_sessions(user_id, chat_type)
                        ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_messages_lookup
                    ON chat_messages(user_id, chat_type, created_at)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_contexts (
                    user_id      TEXT NOT NULL,
                    chat_type    TEXT NOT NULL DEFAULT 'single',
                    context_key  TEXT NOT NULL,
                    context_val  TEXT NOT NULL DEFAULT '',
                    updated_at   TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                    PRIMARY KEY (user_id, chat_type, context_key)
                )
                """);

        return new SqliteChatMemory(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (dataSource instanceof HikariDataSource hds) {
            hds.close();
        }
    }

    // ==================== 核心 CRUD ====================

    @Nested
    @DisplayName("add / get 基本读写")
    class BasicCrud {

        @Test
        @DisplayName("添加单条用户消息后可以读取")
        void add_singleUserMessage_canBeRetrieved() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(new UserMessage("你好")));

            List<Message> messages = memory.get(CONVERSATION_ID);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.USER);
            assertThat(messages.get(0).getText()).isEqualTo("你好");
        }

        @Test
        @DisplayName("添加多条消息后按时间正序返回")
        void add_multipleMessages_returnsInOrder() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(
                    new UserMessage("问题1"),
                    new AssistantMessage("回答1"),
                    new UserMessage("问题2"),
                    new AssistantMessage("回答2")
            ));

            List<Message> messages = memory.get(CONVERSATION_ID);

            assertThat(messages).hasSize(4);
            assertThat(messages.get(0).getText()).isEqualTo("问题1");
            assertThat(messages.get(1).getText()).isEqualTo("回答1");
            assertThat(messages.get(2).getText()).isEqualTo("问题2");
            assertThat(messages.get(3).getText()).isEqualTo("回答2");
        }

        @Test
        @DisplayName("支持 system 类型消息")
        void add_systemMessage_isStored() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(
                    new SystemMessage("你是智能助手"),
                    new UserMessage("你好")
            ));

            List<Message> messages = memory.get(CONVERSATION_ID);

            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
            assertThat(messages.get(0).getText()).isEqualTo("你是智能助手");
            assertThat(messages.get(1).getMessageType()).isEqualTo(MessageType.USER);
        }

        @Test
        @DisplayName("批量添加自动更新会话消息计数")
        void add_batch_updatesMsgCount() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(
                    new UserMessage("问题1"),
                    new AssistantMessage("回答1")
            ));

            Integer count = jdbc.queryForObject(
                    "SELECT msg_count FROM chat_sessions WHERE user_id = ? AND chat_type = ?",
                    Integer.class, USER_ID, CHAT_TYPE);
            assertThat(count).isEqualTo(2);

            memory.add(CONVERSATION_ID, List.of(
                    new UserMessage("问题2"),
                    new AssistantMessage("回答2")
            ));

            count = jdbc.queryForObject(
                    "SELECT msg_count FROM chat_sessions WHERE user_id = ? AND chat_type = ?",
                    Integer.class, USER_ID, CHAT_TYPE);
            assertThat(count).isEqualTo(4);
        }
    }

    // ==================== 自动创建会话 ====================

    @Nested
    @DisplayName("会话自动创建")
    class SessionAutoCreate {

        @Test
        @DisplayName("首次 add 自动创建会话记录")
        void add_autoCreatesSession() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(new UserMessage("你好")));

            var session = jdbc.queryForMap(
                    "SELECT * FROM chat_sessions WHERE user_id = ? AND chat_type = ?",
                    USER_ID, CHAT_TYPE);

            assertThat(session.get("status")).isEqualTo("active");
            assertThat(session.get("msg_count")).isEqualTo(1);
            assertThat(session.get("window_size")).isEqualTo(20);
        }

        @Test
        @DisplayName("重复 add 不会重复创建会话")
        void add_repeatedAdd_doesNotDuplicateSession() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(new UserMessage("第一次")));
            memory.add(CONVERSATION_ID, List.of(new UserMessage("第二次")));

            List<?> sessions = jdbc.queryForList(
                    "SELECT * FROM chat_sessions WHERE user_id = ? AND chat_type = ?",
                    USER_ID, CHAT_TYPE);

            assertThat(sessions).hasSize(1);
        }
    }

    // ==================== getLastN ====================

    @Nested
    @DisplayName("getLastN 窗口查询")
    class GetLastN {

        @Test
        @DisplayName("返回最近 N 条消息，按时间正序")
        void getLastN_returnsRecentN() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(
                    new UserMessage("问题1"),
                    new AssistantMessage("回答1"),
                    new UserMessage("问题2"),
                    new AssistantMessage("回答2"),
                    new UserMessage("问题3"),
                    new AssistantMessage("回答3")
            ));

            List<Message> last2 = memory.getLastN(CONVERSATION_ID, 2);

            assertThat(last2).hasSize(2);
            assertThat(last2.get(0).getText()).isEqualTo("问题3");
            assertThat(last2.get(1).getText()).isEqualTo("回答3");
        }

        @Test
        @DisplayName("消息数少于 N 时返回全部")
        void getLastN_lessMessagesThanN_returnsAll() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(new UserMessage("只有一条")));

            List<Message> result = memory.getLastN(CONVERSATION_ID, 10);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getText()).isEqualTo("只有一条");
        }

        @Test
        @DisplayName("空会话返回空列表")
        void getLastN_emptyConversation_returnsEmpty() {
            memory = createMemory();

            List<Message> result = memory.getLastN(CONVERSATION_ID, 5);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("N 为 0 时返回空列表")
        void getLastN_zero_returnsEmpty() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(new UserMessage("你好")));

            List<Message> result = memory.getLastN(CONVERSATION_ID, 0);

            assertThat(result).isEmpty();
        }
    }

    // ==================== clear ====================

    @Nested
    @DisplayName("clear 清除操作")
    class Clear {

        @Test
        @DisplayName("清除消息并重置计数")
        void clear_deletesMessagesAndResetsCount() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(
                    new UserMessage("问题1"),
                    new AssistantMessage("回答1")
            ));

            memory.clear(CONVERSATION_ID);

            List<Message> messages = memory.get(CONVERSATION_ID);
            assertThat(messages).isEmpty();

            Integer count = jdbc.queryForObject(
                    "SELECT msg_count FROM chat_sessions WHERE user_id = ? AND chat_type = ?",
                    Integer.class, USER_ID, CHAT_TYPE);
            assertThat(count).isZero();
        }

        @Test
        @DisplayName("清除空会话不会报错")
        void clear_emptySession_doesNotThrow() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(new UserMessage("你好")));
            memory.clear(CONVERSATION_ID);

            assertThatCode(() -> memory.clear(CONVERSATION_ID))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== getAsConversationMessages ====================

    @Nested
    @DisplayName("getAsConversationMessages 转换")
    class AsConversationMessages {

        @Test
        @DisplayName("将 Message 列表转换为 ConversationMessage 列表")
        void convertToConversationMessages() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(
                    new UserMessage("你好"),
                    new AssistantMessage("有什么可以帮您？")
            ));

            var result = memory.getAsConversationMessages(CONVERSATION_ID, 10);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).role()).isEqualTo(
                    com.example.ykdsummer.ai.model.ConversationMessage.Role.USER);
            assertThat(result.get(0).text()).isEqualTo("你好");
            assertThat(result.get(1).role()).isEqualTo(
                    com.example.ykdsummer.ai.model.ConversationMessage.Role.ASSISTANT);
            assertThat(result.get(1).text()).isEqualTo("有什么可以帮您？");
        }
    }

    // ==================== getWindowSize ====================

    @Nested
    @DisplayName("getWindowSize 窗口大小")
    class WindowSize {

        @Test
        @DisplayName("会话不存在时返回默认值")
        void noSession_returnsDefault() {
            memory = createMemory();

            int size = memory.getWindowSize(CONVERSATION_ID, 15);

            assertThat(size).isEqualTo(15);
        }

        @Test
        @DisplayName("会话创建后返回表中配置的值")
        void sessionExists_returnsConfiguredValue() {
            memory = createMemory();
            memory.add(CONVERSATION_ID, List.of(new UserMessage("你好")));

            // 修改窗口大小
            jdbc.update("UPDATE chat_sessions SET window_size = 50 WHERE user_id = ? AND chat_type = ?",
                    USER_ID, CHAT_TYPE);

            int size = memory.getWindowSize(CONVERSATION_ID, 20);

            assertThat(size).isEqualTo(50);
        }
    }

    // ==================== 多用户隔离 ====================

    @Nested
    @DisplayName("多用户隔离")
    class MultiUserIsolation {

        @Test
        @DisplayName("不同用户的消息互不干扰")
        void differentUsers_dontInterfere() {
            memory = createMemory();
            String userA = "user_a::single";
            String userB = "user_b::single";

            memory.add(userA, List.of(new UserMessage("A的问题")));
            memory.add(userB, List.of(
                    new UserMessage("B的问题"),
                    new AssistantMessage("B的答案")
            ));

            List<Message> msgsA = memory.get(userA);
            List<Message> msgsB = memory.get(userB);

            assertThat(msgsA).hasSize(1);
            assertThat(msgsA.get(0).getText()).isEqualTo("A的问题");
            assertThat(msgsB).hasSize(2);
        }

        @Test
        @DisplayName("清除用户 A 不影响用户 B")
        void clearUserA_doesNotAffectUserB() {
            memory = createMemory();
            String userA = "user_a::single";
            String userB = "user_b::single";

            memory.add(userA, List.of(new UserMessage("A的问题")));
            memory.add(userB, List.of(new UserMessage("B的问题")));

            memory.clear(userA);

            assertThat(memory.get(userA)).isEmpty();
            assertThat(memory.get(userB)).hasSize(1);
        }
    }

    // ==================== 内部方法测试 ====================

    @Nested
    @DisplayName("parseConversationId")
    class ParseConversationId {

        @Test
        @DisplayName("正确解析 userId::chatType 格式")
        void validFormat_parsesCorrectly() {
            String[] parts = SqliteChatMemory.parseConversationId("wx_user_abc::group");

            assertThat(parts).containsExactly("wx_user_abc", "group");
        }

        @Test
        @DisplayName("没有分隔符时回退：userId=原始字符串, chatType=single")
        void noSeparator_fallsBack() {
            String[] parts = SqliteChatMemory.parseConversationId("wx_user_abc");

            assertThat(parts).containsExactly("wx_user_abc", "single");
        }

        @Test
        @DisplayName("空字符串回退")
        void emptyString_fallsBack() {
            String[] parts = SqliteChatMemory.parseConversationId("");

            assertThat(parts).containsExactly("", "single");
        }
    }

    @Nested
    @DisplayName("toRoleString / toMessageType 角色转换")
    class RoleConversion {

        @Test
        @DisplayName("角色字符串与枚举互转对称")
        void roundTrip_symmetric() {
            assertThat(SqliteChatMemory.toRoleString(MessageType.USER)).isEqualTo("user");
            assertThat(SqliteChatMemory.toRoleString(MessageType.ASSISTANT)).isEqualTo("assistant");
            assertThat(SqliteChatMemory.toRoleString(MessageType.SYSTEM)).isEqualTo("system");
        }

        @Test
        @DisplayName("toRoleString 未知类型默认 user")
        void unknownType_defaultsToUser() {
            assertThat(SqliteChatMemory.toRoleString(MessageType.TOOL)).isEqualTo("user");
        }
    }

    // ==================== 边界情况 ====================

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("添加空消息列表不会报错")
        void add_emptyList_doesNotThrow() {
            memory = createMemory();

            assertThatCode(() -> memory.add(CONVERSATION_ID, List.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("对大文本内容能正确处理")
        void add_largeContent() {
            memory = createMemory();
            String large = "A".repeat(10_000);

            assertThatCode(() -> memory.add(CONVERSATION_ID, List.of(new UserMessage(large))))
                    .doesNotThrowAnyException();

            List<Message> messages = memory.get(CONVERSATION_ID);
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getText()).hasSize(10_000);
        }

        @Test
        @DisplayName("不存在的会话 get 返回空列表")
        void get_nonExistent_returnsEmpty() {
            memory = createMemory();

            List<Message> messages = memory.get(CONVERSATION_ID);

            assertThat(messages).isEmpty();
        }
    }

    // ==================== chat_type 隔离 ====================

    @Nested
    @DisplayName("chatType 隔离（单聊 vs 群聊）")
    class ChatTypeIsolation {

        @Test
        @DisplayName("同一用户的不同 chatType 互不干扰")
        void sameUser_differentChatType_isolated() {
            memory = createMemory();
            String singleConv = "user_x::single";
            String groupConv = "user_x::group";

            memory.add(singleConv, List.of(new UserMessage("私聊消息")));
            memory.add(groupConv, List.of(new UserMessage("群聊消息")));

            assertThat(memory.get(singleConv)).hasSize(1);
            assertThat(memory.get(singleConv).get(0).getText()).isEqualTo("私聊消息");
            assertThat(memory.get(groupConv)).hasSize(1);
            assertThat(memory.get(groupConv).get(0).getText()).isEqualTo("群聊消息");
        }

        @Test
        @DisplayName("clear 只清除指定 chatType")
        void clear_onlyClearsSpecifiedChatType() {
            memory = createMemory();
            String singleConv = "user_y::single";
            String groupConv = "user_y::group";

            memory.add(singleConv, List.of(new UserMessage("私聊")));
            memory.add(groupConv, List.of(new UserMessage("群聊")));

            memory.clear(singleConv);

            assertThat(memory.get(singleConv)).isEmpty();
            assertThat(memory.get(groupConv)).hasSize(1);
        }
    }
}
