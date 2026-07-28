package com.example.ykdsummer.storage.db;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.AiUsageProperties;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.service.AiModelUsage;
import com.example.ykdsummer.ai.service.AiRequestBudget;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.service.AiUsageMeter;
import com.example.ykdsummer.ai.service.LlmGateway;
import com.example.ykdsummer.ai.service.TokenBudgetPolicy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatServiceSqliteTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private SqliteChatMemory chatMemory;
    private RecordingGateway gateway;
    private AiChatService service;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_sessions (user_id TEXT NOT NULL, chat_type TEXT NOT NULL DEFAULT 'single' CHECK(chat_type IN ('single', 'group')), nick_name TEXT DEFAULT '', status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active', 'archived')), msg_count INTEGER NOT NULL DEFAULT 0, window_size INTEGER NOT NULL DEFAULT 20, created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), PRIMARY KEY (user_id, chat_type))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, chat_type TEXT NOT NULL DEFAULT 'single', role TEXT NOT NULL CHECK(role IN ('user','assistant','system')), content TEXT NOT NULL, msg_type TEXT DEFAULT 'text', wx_msg_id TEXT DEFAULT '', token_count INTEGER DEFAULT 0, created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), FOREIGN KEY (user_id, chat_type) REFERENCES chat_sessions(user_id, chat_type) ON DELETE CASCADE)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_messages_lookup ON chat_messages(user_id, chat_type, created_at)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_contexts (user_id TEXT NOT NULL, chat_type TEXT NOT NULL DEFAULT 'single', context_key TEXT NOT NULL, context_val TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), PRIMARY KEY (user_id, chat_type, context_key))");

        chatMemory = new SqliteChatMemory(jdbc);
        gateway = new RecordingGateway();

        AiProperties properties = new AiProperties();
        AiUsageProperties usageProperties = new AiUsageProperties();

        service = new AiChatService(
                properties, gateway,
                AiTraceLogger.disabled(),
                AiUsageMeter.disabled(),
                new TokenBudgetPolicy(usageProperties),
                chatMemory
        );
    }

    @AfterEach
    void tearDown() {
        if (dataSource instanceof HikariDataSource hds) {
            hds.close();
        }
    }

    @Test
    void answer_storesMessagesInSqlite() {
        service.answer("user-1", "hello", List.of());
        var history = chatMemory.getAsConversationMessages("user-1::single", 10);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(history.get(0).text()).isEqualTo("hello");
        assertThat(history.get(1).role()).isEqualTo(ConversationMessage.Role.ASSISTANT);
    }

    @Test
    void answer_buildsContextFromPreviousTurns() {
        service.answer("user-1", "first", List.of());
        service.answer("user-1", "second", List.of());
        assertThat(gateway.requests.get(0).history).isEmpty();
        assertThat(gateway.requests.get(1).history)
                .extracting(ConversationMessage::text)
                .containsExactly("first", "reply#1");
    }

    @Test
    void clear_removesConversationHistory() {
        service.answer("user-1", "question", List.of());
        service.clear("user-1");
        service.answer("user-1", "after clear", List.of());
        assertThat(gateway.requests.get(1).history).isEmpty();
    }

    @Test
    void differentUsers_haveIsolatedHistories() {
        service.answer("user-a", "A first", List.of());
        service.answer("user-b", "B first", List.of());
        service.answer("user-a", "A second", List.of());
        assertThat(gateway.requests.get(0).history).isEmpty();
        assertThat(gateway.requests.get(1).history).isEmpty();
        assertThat(gateway.requests.get(2).history)
                .extracting(ConversationMessage::text)
                .containsExactly("A first", "reply#1");
    }

    @Test
    void clear_onlyAffectsSpecifiedUser() {
        service.answer("user-a", "A first", List.of());
        service.answer("user-b", "B first", List.of());
        service.clear("user-a");
        service.answer("user-a", "A new", List.of());
        service.answer("user-b", "B second", List.of());
        assertThat(gateway.requests.get(2).history).isEmpty();
        assertThat(gateway.requests.get(3).history)
                .extracting(ConversationMessage::text)
                .containsExactly("B first", "reply#2");
    }

    @Test
    void memoryPromptIncludesImageHint() {
        var image = new AiImage("image/png", new byte[]{1, 2, 3});
        service.answer("user-1", "analyze", List.of(image));
        var history = chatMemory.getAsConversationMessages("user-1::single", 10);
        assertThat(history.get(0).text()).contains("analyze");
    }

    @Test
    void filesAreNotStoredInHistoryButFilenameIsNoted() {
        var file = new AiFile("report.pdf", "application/pdf", new byte[]{1, 2, 3});
        service.answer("user-1", "summarize", List.of(), List.of(file));
        service.answer("user-1", "continue", List.of());
        assertThat(gateway.requests.get(0).files).containsExactly(file);
        assertThat(gateway.requests.get(1).files).isEmpty();
        var history = chatMemory.getAsConversationMessages("user-1::single", 10);
        assertThat(history.get(0).text()).startsWith("summarize");
        assertThat(history.get(2).text()).startsWith("continue");
    }

    private static final class RecordingGateway implements LlmGateway {
        private final List<Request> requests = new ArrayList<>();
        private int counter;

        @Override
        public ModelReply generate(
                String userId,
                List<ConversationMessage> history,
                String prompt,
                List<AiImage> images,
                List<AiFile> files,
                AiRequestBudget budget
        ) {
            counter++;
            requests.add(new Request(List.copyOf(history), List.copyOf(images), List.copyOf(files)));
            return new ModelReply("reply#" + counter, "test-model", List.of(),
                    AiModelUsage.unknown(), "chat-completions");
        }

        @Override
        public ModelReply generate(
                List<ConversationMessage> history,
                String prompt,
                List<AiImage> images,
                List<AiFile> files
        ) {
            return generate("", history, prompt, images, files, null);
        }
    }

    private record Request(
            List<ConversationMessage> history,
            List<AiImage> images,
            List<AiFile> files
    ) {
    }
}

