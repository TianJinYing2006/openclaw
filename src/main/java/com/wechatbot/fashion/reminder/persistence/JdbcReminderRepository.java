package com.wechatbot.fashion.reminder.persistence;

import com.wechatbot.fashion.persistence.ManagedInstanceScope;
import com.wechatbot.fashion.reminder.domain.Reminder;
import com.wechatbot.fashion.reminder.domain.ReminderDelivery;
import com.wechatbot.fashion.reminder.domain.ReminderDeliveryStatus;
import com.wechatbot.fashion.reminder.domain.ReminderExecutionMode;
import com.wechatbot.fashion.reminder.domain.ReminderScheduleType;
import com.wechatbot.fashion.reminder.domain.ReminderStatus;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL state machine for schedules and at-least-once reminder deliveries. */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcReminderRepository implements ReminderRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcReminderRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Reminder create(Reminder reminder) {
        ManagedInstanceScope scope = ManagedInstanceScope.parse(reminder.externalUserId());
        Long platformUserId = scope.resolvePlatformUserId(jdbc);
        jdbc.update("""
                INSERT INTO reminders(id, external_user_id, platform_user_id, instance_id, reminder_text, execution_mode,
                    task_prompt, schedule_type,
                    zone_id, local_time, weekday, next_fire_at, last_fire_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, reminder.id(), reminder.externalUserId(), platformUserId, scope.instanceId(), reminder.text(),
                reminder.executionMode().name(), reminder.taskPrompt(), reminder.scheduleType().name(),
                reminder.zoneId(), time(reminder.localTime()), reminder.weekday(),
                timestamp(reminder.nextFireAt()), timestamp(reminder.lastFireAt()), reminder.status().name());
        return reminder;
    }

    @Override
    public List<Reminder> activeDue(Instant now, int limit) {
        return jdbc.query("""
                SELECT id, external_user_id, reminder_text, execution_mode, task_prompt, schedule_type, zone_id, local_time, weekday,
                    next_fire_at, last_fire_at, status, created_at
                FROM reminders WHERE status = 'ACTIVE' AND next_fire_at <= ?
                ORDER BY next_fire_at ASC LIMIT ?
                """, (rs, row) -> reminder(rs.getString("id"), rs.getString("external_user_id"),
                rs.getString("reminder_text"), rs.getString("execution_mode"), rs.getString("task_prompt"),
                rs.getString("schedule_type"), rs.getString("zone_id"),
                rs.getTime("local_time"), integer(rs.getObject("weekday")), rs.getTimestamp("next_fire_at"),
                rs.getTimestamp("last_fire_at"), rs.getString("status"), rs.getTimestamp("created_at")), timestamp(now), limit);
    }

    @Override
    public void materializeDueReminder(Reminder reminder, Instant nextFireAt) {
        transactions.executeWithoutResult(status -> {
            List<String> dueRows = jdbc.queryForList("""
                    SELECT id FROM reminders
                    WHERE id = ? AND status = 'ACTIVE' AND next_fire_at = ? FOR UPDATE
                    """, String.class, reminder.id(), timestamp(reminder.nextFireAt()));
            if (dueRows.isEmpty()) return;
            int inserted = jdbc.update("""
                    INSERT IGNORE INTO reminder_deliveries(id, reminder_id, external_user_id, scheduled_for,
                        message_snapshot, execution_mode, task_prompt_snapshot, status, next_attempt_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    """, UUID.randomUUID().toString(), reminder.id(), reminder.externalUserId(),
                    timestamp(reminder.nextFireAt()), reminder.text(), reminder.executionMode().name(),
                    reminder.taskPrompt(), timestamp(reminder.nextFireAt()));
            if (inserted == 0) return;
            if (nextFireAt == null) {
                jdbc.update("""
                        UPDATE reminders SET status = 'COMPLETED', last_fire_at = ?, next_fire_at = NULL,
                            updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'ACTIVE' AND next_fire_at = ?
                        """, timestamp(reminder.nextFireAt()), reminder.id(), timestamp(reminder.nextFireAt()));
            } else {
                jdbc.update("""
                        UPDATE reminders SET last_fire_at = ?, next_fire_at = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status = 'ACTIVE' AND next_fire_at = ?
                        """, timestamp(reminder.nextFireAt()), timestamp(nextFireAt), reminder.id(), timestamp(reminder.nextFireAt()));
            }
        });
    }

    @Override
    public List<ReminderDelivery> claimDueDeliveries(Instant now, int limit) {
        return transactions.execute(status -> {
            List<ReminderDelivery> deliveries = jdbc.query("""
                    SELECT d.id, d.reminder_id, d.external_user_id, d.scheduled_for, d.message_snapshot,
                        CASE
                            WHEN COALESCE(NULLIF(d.task_prompt_snapshot, ''), NULLIF(r.task_prompt, '')) IS NOT NULL
                                THEN 'AGENT'
                            ELSE d.execution_mode
                        END AS execution_mode,
                        COALESCE(NULLIF(d.task_prompt_snapshot, ''), NULLIF(r.task_prompt, '')) AS task_prompt_snapshot,
                        d.status, d.attempt_count
                    FROM reminder_deliveries d
                    JOIN reminders r ON r.id = d.reminder_id
                    WHERE d.status IN ('PENDING', 'RETRY', 'WAITING_CONTEXT') AND d.next_attempt_at <= ?
                    ORDER BY d.next_attempt_at ASC LIMIT ? FOR UPDATE
                    """, (rs, row) -> delivery(rs.getString("id"), rs.getString("reminder_id"),
                    rs.getString("external_user_id"), rs.getTimestamp("scheduled_for"), rs.getString("message_snapshot"),
                    rs.getString("execution_mode"), rs.getString("task_prompt_snapshot"), rs.getString("status"),
                    rs.getInt("attempt_count")), timestamp(now), limit);
            for (ReminderDelivery delivery : deliveries) {
                jdbc.update("""
                        UPDATE reminder_deliveries SET status = 'PROCESSING', claimed_at = ?, next_attempt_at = NULL,
                            updated_at = CURRENT_TIMESTAMP WHERE id = ?
                        """, timestamp(now), delivery.id());
            }
            return deliveries;
        });
    }

    @Override
    public int beginDeliveryAttempt(String deliveryId) {
        jdbc.update("""
                UPDATE reminder_deliveries SET attempt_count = attempt_count + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING'
                """, deliveryId);
        Integer attempts = jdbc.queryForObject("SELECT attempt_count FROM reminder_deliveries WHERE id = ?", Integer.class, deliveryId);
        return attempts == null ? 0 : attempts;
    }

    @Override
    public void markSent(String deliveryId, Instant sentAt) {
        jdbc.update("""
                UPDATE reminder_deliveries SET status = 'SENT', sent_at = ?, claimed_at = NULL, next_attempt_at = NULL,
                    failure_summary = '', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PROCESSING'
                """, timestamp(sentAt), deliveryId);
    }

    @Override
    public void markWaitingForContext(String deliveryId, Instant nextAttemptAt) {
        jdbc.update("""
                UPDATE reminder_deliveries SET status = 'WAITING_CONTEXT', claimed_at = NULL, next_attempt_at = ?,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PROCESSING'
                """, timestamp(nextAttemptAt), deliveryId);
    }

    @Override
    public void markRetry(String deliveryId, Instant nextAttemptAt, String failureSummary) {
        jdbc.update("""
                UPDATE reminder_deliveries SET status = 'RETRY', claimed_at = NULL, next_attempt_at = ?, failure_summary = ?,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PROCESSING'
                """, timestamp(nextAttemptAt), safeFailure(failureSummary), deliveryId);
    }

    @Override
    public void markFailed(String deliveryId, String failureSummary) {
        jdbc.update("""
                UPDATE reminder_deliveries SET status = 'FAILED', claimed_at = NULL, next_attempt_at = NULL, failure_summary = ?,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PROCESSING'
                """, safeFailure(failureSummary), deliveryId);
    }

    @Override
    public void recoverExpiredProcessing(Instant claimedBefore) {
        jdbc.update("""
                UPDATE reminder_deliveries SET status = 'RETRY', next_attempt_at = CURRENT_TIMESTAMP,
                    claimed_at = NULL, failure_summary = '后台任务中断，已恢复重试', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'PROCESSING' AND claimed_at < ?
                """, timestamp(claimedBefore));
    }

    @Override
    public int recoverInterruptedProcessingOnStartup() {
        return jdbc.update("""
                UPDATE reminder_deliveries SET status = 'RETRY', next_attempt_at = CURRENT_TIMESTAMP,
                    claimed_at = NULL, failure_summary = '服务重启，已恢复重试', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'PROCESSING'
                """);
    }

    @Override
    public List<Reminder> listForUser(String externalUserId, int limit) {
        return jdbc.query("""
                SELECT id, external_user_id, reminder_text, execution_mode, task_prompt, schedule_type, zone_id, local_time, weekday,
                    next_fire_at, last_fire_at, status, created_at
                FROM reminders WHERE external_user_id = ?
                ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END, next_fire_at ASC, created_at DESC LIMIT ?
                """, (rs, row) -> reminder(rs.getString("id"), rs.getString("external_user_id"),
                rs.getString("reminder_text"), rs.getString("execution_mode"), rs.getString("task_prompt"),
                rs.getString("schedule_type"), rs.getString("zone_id"),
                rs.getTime("local_time"), integer(rs.getObject("weekday")), rs.getTimestamp("next_fire_at"),
                rs.getTimestamp("last_fire_at"), rs.getString("status"), rs.getTimestamp("created_at")), externalUserId, limit);
    }

    @Override
    public boolean cancel(String externalUserId, String reminderId) {
        return jdbc.update("""
                UPDATE reminders SET status = 'CANCELLED', next_fire_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND external_user_id = ? AND status = 'ACTIVE'
                """, reminderId, externalUserId) > 0;
    }

    private static Reminder reminder(String id, String userId, String text, String executionMode, String taskPrompt,
                                     String type, String zoneId, Time localTime,
                                     Integer weekday, Timestamp nextFireAt, Timestamp lastFireAt, String status, Timestamp createdAt) {
        return new Reminder(id, userId, text, executionMode(executionMode), taskPrompt, ReminderScheduleType.valueOf(type), zoneId,
                localTime == null ? null : localTime.toLocalTime(), weekday, instant(nextFireAt), instant(lastFireAt),
                ReminderStatus.valueOf(status), instant(createdAt));
    }

    private static ReminderDelivery delivery(String id, String reminderId, String userId, Timestamp scheduledFor,
                                             String message, String executionMode, String taskPrompt, String status, int attempts) {
        return new ReminderDelivery(id, reminderId, userId, instant(scheduledFor), message,
                executionMode(executionMode), taskPrompt,
                ReminderDeliveryStatus.valueOf(status), attempts);
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Time time(LocalTime value) { return value == null ? null : Time.valueOf(value); }
    private static Integer integer(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private static ReminderExecutionMode executionMode(String value) {
        return value == null || value.isBlank() ? ReminderExecutionMode.MESSAGE : ReminderExecutionMode.valueOf(value);
    }
    private static String safeFailure(String value) {
        String safe = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return safe.length() <= 512 ? safe : safe.substring(0, 512);
    }
}
