package com.example.ykdsummer.reminder.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.reminder.domain.Reminder;
import com.example.ykdsummer.reminder.domain.ReminderDelivery;
import com.example.ykdsummer.reminder.domain.ReminderExecutionMode;
import com.example.ykdsummer.reminder.domain.ReminderScheduleType;
import com.example.ykdsummer.reminder.domain.ReminderStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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

/** Explicit opt-in local MySQL test for durable Agent scheduling and restart recovery. */
@EnabledIfEnvironmentVariable(named = "PERSISTENCE_INTEGRATION", matches = "true")
class ReminderRepositoryIntegrationTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcReminderRepository repository;
    private String externalUserId;

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
        repository = new JdbcReminderRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        externalUserId = "reminder-test-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && externalUserId != null) {
            jdbc.update("DELETE FROM reminder_deliveries WHERE external_user_id = ?", externalUserId);
            jdbc.update("DELETE FROM reminders WHERE external_user_id = ?", externalUserId);
        }
        if (dataSource != null) dataSource.close();
    }

    @Test
    void persistsAgentModeAndRehydratesItForDueDelivery() {
        Instant due = Instant.now().minusSeconds(1);
        Reminder reminder = agentReminder("查询杭州天气并规划路线", due);
        repository.create(reminder);
        Reminder persistedDue = repository.activeDue(Instant.now(), 10).getFirst();
        repository.materializeDueReminder(persistedDue, null);

        assertThat(jdbc.queryForObject("SELECT execution_mode FROM reminders WHERE id = ?", String.class, reminder.id()))
                .isEqualTo("AGENT");
        List<ReminderDelivery> claimed = repository.claimDueDeliveries(Instant.now(), 10);

        assertThat(claimed).singleElement().satisfies(delivery -> {
            assertThat(delivery.executionMode()).isEqualTo(ReminderExecutionMode.AGENT);
            assertThat(delivery.taskPrompt()).isEqualTo("查询杭州天气并规划路线");
        });
    }

    @Test
    void upgradesLegacyMessageDeliveryWhenItsParentHasAnAgentPrompt() {
        Instant due = Instant.now().minusSeconds(1);
        Reminder reminder = agentReminder("查询杭州天气", due);
        repository.create(reminder);
        String deliveryId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO reminder_deliveries(id, reminder_id, external_user_id, scheduled_for, message_snapshot,
                    execution_mode, task_prompt_snapshot, status, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, 'MESSAGE', NULL, 'PENDING', ?)
                """, deliveryId, reminder.id(), externalUserId, java.sql.Timestamp.from(due), reminder.text(),
                java.sql.Timestamp.from(due));

        List<ReminderDelivery> claimed = repository.claimDueDeliveries(Instant.now(), 10);

        assertThat(claimed).singleElement().satisfies(delivery -> {
            assertThat(delivery.id()).isEqualTo(deliveryId);
            assertThat(delivery.executionMode()).isEqualTo(ReminderExecutionMode.AGENT);
            assertThat(delivery.taskPrompt()).isEqualTo("查询杭州天气");
        });
    }

    @Test
    void startupRecoveryImmediatelyReturnsInterruptedProcessingDeliveryToRetry() {
        Instant due = Instant.now().minusSeconds(1);
        Reminder reminder = agentReminder("提醒我喝水", due);
        repository.create(reminder);
        Reminder persistedDue = repository.activeDue(Instant.now(), 10).getFirst();
        repository.materializeDueReminder(persistedDue, null);
        ReminderDelivery delivery = repository.claimDueDeliveries(Instant.now(), 10).getFirst();

        assertThat(repository.recoverInterruptedProcessingOnStartup()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM reminder_deliveries WHERE id = ?", String.class, delivery.id()))
                .isEqualTo("RETRY");
    }

    private Reminder agentReminder(String taskPrompt, Instant due) {
        return new Reminder(UUID.randomUUID().toString(), externalUserId, taskPrompt, ReminderExecutionMode.AGENT,
                taskPrompt, ReminderScheduleType.ONCE, "Asia/Shanghai", null, null, due, null,
                ReminderStatus.ACTIVE, Instant.now());
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for integration tests");
        return value;
    }
}
