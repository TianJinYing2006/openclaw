package com.example.ykdsummer.persistence;

import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.ImageTask;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Operation;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Status;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit opt-in only: it writes a temporary row to the local MySQL instance and removes it afterwards.
 * Run with PERSISTENCE_INTEGRATION=true and PERSISTENCE_PASSWORD set outside source control.
 */
@EnabledIfEnvironmentVariable(named = "PERSISTENCE_INTEGRATION", matches = "true")
class PersistenceStoreIntegrationTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private String userId;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv().getOrDefault("PERSISTENCE_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3306/ykd_summer?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"));
        config.setUsername(System.getenv().getOrDefault("PERSISTENCE_USERNAME", "root"));
        config.setPassword(requiredEnvironment("PERSISTENCE_PASSWORD"));
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        userId = "persistence-test-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && userId != null) {
            jdbc.update("DELETE FROM asset_versions WHERE external_user_id = ?", userId);
            jdbc.update("DELETE e FROM task_events e JOIN async_tasks t ON t.task_id = e.task_id WHERE t.external_user_id = ?", userId);
            jdbc.update("DELETE FROM async_tasks WHERE external_user_id = ?", userId);
            jdbc.update("DELETE m FROM chat_messages m JOIN chat_conversations c ON c.id = m.conversation_id WHERE c.external_user_id = ?", userId);
            jdbc.update("DELETE FROM chat_conversations WHERE external_user_id = ?", userId);
            jdbc.update("DELETE FROM app_users WHERE external_user_id = ?", userId);
        }
        if (dataSource != null) dataSource.close();
    }

    @Test
    void persistsConversationTaskAndAssetMetadata() {
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ConversationHistoryStore conversations = new JdbcConversationHistoryStore(jdbc, transactions);
        conversations.appendTurn(userId,
                new ConversationMessage(ConversationMessage.Role.USER, "保存测试问题"),
                new ConversationMessage(ConversationMessage.Role.ASSISTANT, "保存测试回答"));
        assertEquals(List.of("保存测试问题", "保存测试回答"),
                conversations.load(userId, 10).stream().map(ConversationMessage::text).toList());

        ImageTaskPersistence tasks = new JdbcImageTaskPersistence(jdbc);
        ImageTask task = new ImageTask("imgtask_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                Operation.GENERATE, Status.RUNNING, Instant.now(), null, "测试图片", "", 0, "", 0, "");
        tasks.save(userId, task);
        assertTrue(tasks.find(userId, task.taskId()).isPresent());

        ImageAssetMetadataStore assets = new JdbcImageAssetMetadataStore(jdbc);
        assets.record(userId, new StoredImage("img_test_asset", 1, Path.of("ilink-bot/images/test.png"),
                "测试图片", "", Instant.now(), "image/png", "generated", ""), "oss");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM asset_versions WHERE external_user_id = ?", Integer.class, userId);
        assertEquals(1, count);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for integration tests");
        return value;
    }
}
