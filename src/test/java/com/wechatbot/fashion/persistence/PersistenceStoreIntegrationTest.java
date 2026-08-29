package com.wechatbot.fashion.persistence;

import com.wechatbot.fashion.admin.config.AdminWebProperties;
import com.wechatbot.fashion.admin.service.AdminPlatformService;
import com.wechatbot.fashion.ai.model.ConversationMessage;
import com.wechatbot.fashion.ai.service.ImageTaskStatusStore.ImageTask;
import com.wechatbot.fashion.ai.service.ImageTaskStatusStore.Operation;
import com.wechatbot.fashion.ai.service.ImageTaskStatusStore.Status;
import com.wechatbot.fashion.ai.service.LocalImageAssetStore.StoredImage;
import com.wechatbot.fashion.common.security.TokenCipher;
import com.wechatbot.fashion.common.security.TokenEncryptionProperties;
import com.wechatbot.fashion.reminder.domain.Reminder;
import com.wechatbot.fashion.reminder.domain.ReminderDelivery;
import com.wechatbot.fashion.reminder.domain.ReminderScheduleType;
import com.wechatbot.fashion.reminder.domain.ReminderStatus;
import com.wechatbot.fashion.reminder.persistence.JdbcILinkReplyContextPersistence;
import com.wechatbot.fashion.reminder.persistence.JdbcReminderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Explicit opt-in only: it writes a temporary row to the local MySQL instance and removes it afterwards.
 * Run with PERSISTENCE_INTEGRATION=true and PERSISTENCE_PASSWORD set outside source control.
 */
@EnabledIfEnvironmentVariable(named = "PERSISTENCE_INTEGRATION", matches = "true")
class PersistenceStoreIntegrationTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private String userId;
    private String managedInstanceId;
    private long platformUserId;

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
        managedInstanceId = UUID.randomUUID().toString();
        String username = "persistence-test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO platform_users(username, remark) VALUES (?, '')", username);
        Long createdUserId = jdbc.queryForObject("SELECT id FROM platform_users WHERE username = ?", Long.class, username);
        if (createdUserId == null) throw new IllegalStateException("Could not create integration platform user");
        platformUserId = createdUserId;
        jdbc.update("INSERT INTO bot_instances(id, platform_user_id) VALUES (?, ?)", managedInstanceId, platformUserId);
        userId = "managed:" + managedInstanceId + ":persistence-test-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && userId != null) {
            jdbc.update("DELETE FROM ai_usage_events WHERE external_user_id = ?", userId);
            jdbc.update("DELETE d FROM reminder_deliveries d JOIN reminders r ON r.id = d.reminder_id WHERE r.external_user_id = ?", userId);
            jdbc.update("DELETE FROM reminders WHERE external_user_id = ?", userId);
            jdbc.update("DELETE FROM ilink_reply_contexts WHERE external_user_id = ?", userId);
            jdbc.update("DELETE FROM asset_versions WHERE external_user_id = ?", userId);
            jdbc.update("DELETE e FROM task_events e JOIN async_tasks t ON t.task_id = e.task_id WHERE t.external_user_id = ?", userId);
            jdbc.update("DELETE FROM async_tasks WHERE external_user_id = ?", userId);
            jdbc.update("DELETE m FROM chat_messages m JOIN chat_conversations c ON c.id = m.conversation_id WHERE c.external_user_id = ?", userId);
            jdbc.update("DELETE FROM chat_conversations WHERE external_user_id = ?", userId);
            jdbc.update("DELETE FROM app_users WHERE external_user_id = ?", userId);
            jdbc.update("DELETE FROM bot_instances WHERE id = ?", managedInstanceId);
            jdbc.update("DELETE FROM platform_users WHERE id = ?", platformUserId);
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
        DocumentAssetMetadataStore documents = new JdbcDocumentAssetMetadataStore(jdbc);
        documents.record(userId, new com.wechatbot.fashion.bot.file.LocalDocumentAssetStore.StoredDocument(
                "doc_test_asset", 1, 1, Path.of("ilink-bot/documents/test.txt"), "test.txt", "txt", "测试文档", Instant.now()), "oss");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM asset_versions WHERE external_user_id = ?", Integer.class, userId);
        assertEquals(2, count);
        assertEquals(platformUserId, jdbc.queryForObject("SELECT platform_user_id FROM chat_conversations WHERE external_user_id = ?", Long.class, userId));
        assertEquals(managedInstanceId, jdbc.queryForObject("SELECT instance_id FROM chat_conversations WHERE external_user_id = ?", String.class, userId));
        assertEquals(platformUserId, jdbc.queryForObject("SELECT platform_user_id FROM async_tasks WHERE task_id = ?", Long.class, task.taskId()));
        assertEquals(managedInstanceId, jdbc.queryForObject("SELECT instance_id FROM asset_versions WHERE external_user_id = ? AND asset_id = ?", String.class, userId, "img_test_asset"));
        assertEquals("oss", jdbc.queryForObject("SELECT storage_provider FROM asset_versions WHERE external_user_id = ? AND asset_id = ?", String.class, userId, "doc_test_asset"));
    }

    @Test
    void persistsReminderDefinitionDeliveryAndReplyContext() {
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcReminderRepository reminders = new JdbcReminderRepository(jdbc, transactions);
        Instant scheduledFor = Instant.now().minusSeconds(5);
        Reminder reminder = new Reminder(UUID.randomUUID().toString(), userId, "查询杭州天气",
                com.wechatbot.fashion.reminder.domain.ReminderExecutionMode.AGENT, "查询杭州天气",
                ReminderScheduleType.ONCE, ZoneId.of("Asia/Shanghai").getId(), null, null, scheduledFor,
                null, ReminderStatus.ACTIVE, Instant.now());

        reminders.create(reminder);
        assertEquals(1, reminders.listForUser(userId, 10).size());
        List<Reminder> due = reminders.activeDue(Instant.now(), 10);
        assertEquals(1, due.size());
        reminders.materializeDueReminder(due.getFirst(), null);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reminder_deliveries WHERE reminder_id = ?", Integer.class,
                reminder.id()));
        List<ReminderDelivery> deliveries = reminders.claimDueDeliveries(Instant.now(), 10);
        assertEquals(1, deliveries.size());
        assertEquals(com.wechatbot.fashion.reminder.domain.ReminderExecutionMode.AGENT,
                deliveries.getFirst().executionMode());
        assertEquals("查询杭州天气", deliveries.getFirst().taskPrompt());
        assertEquals(1, reminders.beginDeliveryAttempt(deliveries.getFirst().id()));
        reminders.markSent(deliveries.getFirst().id(), Instant.now());
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM reminder_deliveries WHERE id = ?", String.class,
                deliveries.getFirst().id()));

        JdbcILinkReplyContextPersistence contexts = new JdbcILinkReplyContextPersistence(jdbc,
                new TokenCipher(new TokenEncryptionProperties()));
        contexts.save(userId, "test-context-token");
        assertEquals("test-context-token", contexts.find(userId).orElseThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesDurableInstanceUsageForTheAdminDashboard() {
        jdbc.update("INSERT INTO app_users(external_user_id, platform_user_id, instance_id) VALUES (?, ?, ?)",
                userId, platformUserId, managedInstanceId);
        jdbc.update("""
                INSERT INTO ai_usage_events(platform_user_id, instance_id, external_user_id, usage_kind, provider, model,
                    tool_name, prompt_tokens, completion_tokens, total_tokens, quantity, duration_ms, reported)
                VALUES (?, ?, ?, 'MODEL', 'chat-completions', 'test-model', '', 12, 8, 20, 1, 321, true)
                """, platformUserId, managedInstanceId, userId);
        jdbc.update("""
                INSERT INTO ai_usage_events(platform_user_id, instance_id, external_user_id, usage_kind, provider, model,
                    tool_name, prompt_tokens, completion_tokens, total_tokens, quantity, duration_ms, failure_reason, reported)
                VALUES (?, ?, ?, 'MODEL_FAILURE', 'chat-completions', '', '', 0, 0, 0, 1, 654, 'TEMPORARY_UNAVAILABLE', false)
                """, platformUserId, managedInstanceId, userId);
        jdbc.update("""
                INSERT INTO ai_usage_events(platform_user_id, instance_id, external_user_id, usage_kind, provider, model,
                    tool_name, prompt_tokens, completion_tokens, total_tokens, quantity, duration_ms, reported)
                VALUES (?, ?, ?, 'IMAGE_GENERATION', '', 'test-image', 'generate_image', 0, 0, 0, 1, 0, false)
                """, platformUserId, managedInstanceId, userId);
        jdbc.update("""
                INSERT INTO ai_usage_events(platform_user_id, instance_id, external_user_id, usage_kind, provider, model,
                    tool_name, prompt_tokens, completion_tokens, total_tokens, quantity, duration_ms, reported)
                VALUES (?, ?, ?, 'TOOL_SUCCESS', 'spring-ai-tool', '', 'search_web', 0, 0, 0, 1, 120, false)
                """, platformUserId, managedInstanceId, userId);
        jdbc.update("""
                INSERT INTO ai_usage_events(platform_user_id, instance_id, external_user_id, usage_kind, provider, model,
                    tool_name, prompt_tokens, completion_tokens, total_tokens, quantity, duration_ms, reported)
                VALUES (?, ?, ?, 'TOOL_FAILURE', 'spring-ai-tool', '', 'search_web', 0, 0, 0, 1, 80, false)
                """, platformUserId, managedInstanceId, userId);

        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);
        ObjectProvider<TransactionTemplate> transactions = mock(ObjectProvider.class);
        when(transactions.getIfAvailable()).thenReturn(new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        AdminPlatformService service = new AdminPlatformService(jdbcProvider, transactions, new ObjectMapper(), new AdminWebProperties());

        AdminPlatformService.DashboardSnapshot snapshot = service.dashboardSnapshot();
        assertTrue(snapshot.dashboard().todayUsage().totalTokens() >= 20);
        assertTrue(snapshot.dashboard().todayUsage().modelRequests() >= 1);
        assertTrue(snapshot.capturedAt().isAfter(snapshot.usageWindowStart()));
        AdminPlatformService.DashboardInstance instance = snapshot.instances().stream()
                .filter(value -> value.instanceId().equals(managedInstanceId))
                .findFirst().orElseThrow();
        assertEquals(1, instance.modelRequestsToday());
        assertEquals(20, instance.totalTokensToday());
        assertEquals(1, instance.imageOperationsToday());
        assertEquals(2, instance.toolCallsToday());
        assertEquals(1, instance.toolFailuresToday());
        AdminPlatformService.UsageTotals usage = service.usageSummary(managedInstanceId);
        assertEquals(2, usage.toolCalls());
        assertEquals(1, usage.toolFailures());
        AdminPlatformService.ToolUsage tool = service.toolUsage(managedInstanceId).stream()
                .filter(value -> value.toolName().equals("search_web"))
                .findFirst().orElseThrow();
        assertEquals(1, tool.succeededCalls());
        assertEquals(1, tool.failedCalls());
        assertEquals(100, tool.averageDurationMs());
        assertTrue(service.chatUsers().stream().anyMatch(value -> value.externalUserId().equals(userId)
                && value.instanceId().equals(managedInstanceId) && value.modelRequests() == 1 && value.modelFailures() == 1));
        assertTrue(service.modelInvocationsForInstance(managedInstanceId).stream().anyMatch(value -> !value.succeeded()
                && value.durationMs() == 654 && "TEMPORARY_UNAVAILABLE".equals(value.failureReason())));
        Long appUserId = jdbc.queryForObject("SELECT id FROM app_users WHERE external_user_id = ?", Long.class, userId);
        assertTrue(appUserId != null && service.modelInvocationsForChatUser(appUserId).stream()
                .anyMatch(value -> value.succeeded() && value.durationMs() == 321 && "test-model".equals(value.model())));
    }

    @Test
    void marksPersistedInstanceConnectedWhenAnEncryptedSessionIsRestored() {
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);
        ObjectProvider<TransactionTemplate> transactions = mock(ObjectProvider.class);
        when(transactions.getIfAvailable()).thenReturn(new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        AdminPlatformService service = new AdminPlatformService(jdbcProvider, transactions, new ObjectMapper(), new AdminWebProperties());

        service.updateConnection(managedInstanceId, "STARTING", "");
        service.markSessionRestored(managedInstanceId, "restored-account");

        assertEquals("CONNECTED", jdbc.queryForObject(
                "SELECT connection_status FROM bot_instances WHERE id = ?", String.class, managedInstanceId));
        assertEquals("restored-account", jdbc.queryForObject(
                "SELECT ilink_account_id FROM bot_instances WHERE id = ?", String.class, managedInstanceId));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM bot_instance_events WHERE instance_id = ? AND event_type = 'SESSION_RESTORED'",
                Long.class, managedInstanceId) >= 1);
    }

    @Test
    void keepsOneActiveBindingAndAuditsTheFullArchiveRestoreLifecycle() {
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);
        ObjectProvider<TransactionTemplate> transactions = mock(ObjectProvider.class);
        when(transactions.getIfAvailable()).thenReturn(new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        AdminPlatformService service = new AdminPlatformService(jdbcProvider, transactions, new ObjectMapper(), new AdminWebProperties());

        service.archiveActiveInstance(platformUserId, "integration test");

        AdminPlatformService.UserOverview archivedUser = service.findUser(platformUserId).orElseThrow();
        assertNull(archivedUser.instanceId());
        AdminPlatformService.ArchivedInstance archived = service.archivedInstances(platformUserId).stream()
                .filter(value -> value.instanceId().equals(managedInstanceId))
                .findFirst().orElseThrow();
        assertEquals("ARCHIVED", archived.connectionStatus());
        assertTrue(service.eventsForUser(platformUserId).stream()
                .anyMatch(value -> value.instanceId().equals(managedInstanceId) && "INSTANCE_ARCHIVED".equals(value.eventType())));

        service.restoreArchivedInstance(platformUserId, managedInstanceId);

        AdminPlatformService.UserOverview restored = service.findUser(platformUserId).orElseThrow();
        assertEquals(managedInstanceId, restored.instanceId());
        assertEquals("PENDING_QR", restored.lifecycleState());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM bot_instances WHERE platform_user_id = ? AND lifecycle_state <> 'ARCHIVED'",
                Long.class, platformUserId));
        assertTrue(service.eventsForUser(platformUserId).stream()
                .anyMatch(value -> value.instanceId().equals(managedInstanceId) && "INSTANCE_RESTORED".equals(value.eventType())));
    }

    @Test
    void permanentlyDeletingAnArchivedInstanceRemovesItsScopedChatIdentity() {
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);
        ObjectProvider<TransactionTemplate> transactions = mock(ObjectProvider.class);
        when(transactions.getIfAvailable()).thenReturn(new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        AdminPlatformService service = new AdminPlatformService(jdbcProvider, transactions, new ObjectMapper(), new AdminWebProperties());
        jdbc.update("INSERT INTO app_users(external_user_id, platform_user_id, instance_id) VALUES (?, ?, ?)",
                userId, platformUserId, managedInstanceId);
        String username = jdbc.queryForObject("SELECT username FROM platform_users WHERE id = ?", String.class, platformUserId);

        service.archiveActiveInstance(platformUserId, "integration test");
        service.permanentlyDeleteArchivedInstance(platformUserId, managedInstanceId, username, "integration cleanup");

        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE external_user_id = ?", Long.class, userId));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM bot_instances WHERE id = ?", Long.class, managedInstanceId));
    }

    @Test
    void keepsTheSameWechatIdentitySeparatedAcrossDifferentBotInstances() {
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);
        ObjectProvider<TransactionTemplate> transactions = mock(ObjectProvider.class);
        when(transactions.getIfAvailable()).thenReturn(new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        AdminPlatformService service = new AdminPlatformService(jdbcProvider, transactions, new ObjectMapper(), new AdminWebProperties());
        String secondUsername = "persistence-peer-" + UUID.randomUUID();
        String secondInstanceId = UUID.randomUUID().toString();
        String rawWechatIdentity = "same-wechat-user@im.wechat";
        String firstExternalUser = "managed:" + managedInstanceId + ':' + rawWechatIdentity;
        String secondExternalUser = "managed:" + secondInstanceId + ':' + rawWechatIdentity;
        jdbc.update("INSERT INTO platform_users(username, remark) VALUES (?, '')", secondUsername);
        Long secondUserId = jdbc.queryForObject("SELECT id FROM platform_users WHERE username = ?", Long.class, secondUsername);
        if (secondUserId == null) throw new IllegalStateException("Could not create second platform user");
        jdbc.update("INSERT INTO bot_instances(id, platform_user_id) VALUES (?, ?)", secondInstanceId, secondUserId);
        try {
            jdbc.update("INSERT INTO app_users(external_user_id, platform_user_id, instance_id) VALUES (?, ?, ?)",
                    firstExternalUser, platformUserId, managedInstanceId);
            jdbc.update("INSERT INTO app_users(external_user_id, platform_user_id, instance_id) VALUES (?, ?, ?)",
                    secondExternalUser, secondUserId, secondInstanceId);

            List<AdminPlatformService.ChatUserOverview> matching = service.chatUsers().stream()
                    .filter(value -> rawWechatIdentity.equals(value.externalUserId().substring(value.externalUserId().lastIndexOf(':') + 1)))
                    .toList();

            assertEquals(2, matching.size());
            assertTrue(matching.stream().anyMatch(value -> value.instanceId().equals(managedInstanceId)
                    && value.platformUserId() == platformUserId));
            assertTrue(matching.stream().anyMatch(value -> value.instanceId().equals(secondInstanceId)
                    && value.platformUserId() == secondUserId));
        } finally {
            jdbc.update("DELETE FROM app_users WHERE external_user_id IN (?, ?)", firstExternalUser, secondExternalUser);
            jdbc.update("DELETE FROM bot_instances WHERE id = ?", secondInstanceId);
            jdbc.update("DELETE FROM platform_users WHERE id = ?", secondUserId);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for integration tests");
        return value;
    }
}
