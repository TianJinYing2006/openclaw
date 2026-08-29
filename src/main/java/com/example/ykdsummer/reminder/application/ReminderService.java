package com.example.ykdsummer.reminder.application;

import com.example.ykdsummer.reminder.config.ReminderProperties;
import com.example.ykdsummer.reminder.domain.Reminder;
import com.example.ykdsummer.reminder.domain.ReminderDelivery;
import com.example.ykdsummer.reminder.domain.ReminderExecutionMode;
import com.example.ykdsummer.reminder.domain.ReminderScheduleType;
import com.example.ykdsummer.reminder.domain.ReminderStatus;
import com.example.ykdsummer.reminder.persistence.ReminderRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Application boundary for reminder creation, calendar recurrence, and durable delivery state. */
@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class ReminderService {
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter LOCAL_DATE_TIME_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ReminderRepository repository;
    private final ReminderProperties properties;

    public ReminderService(ReminderRepository repository, ReminderProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public Reminder create(String externalUserId, String text, String scheduleType, String onceAt,
                           String timeOfDay, String weekday) {
        return createAgentTask(externalUserId, text, scheduleType, onceAt, timeOfDay, weekday);
    }

    public Reminder createAgentTask(String externalUserId, String taskPrompt, String scheduleType, String onceAt,
                                    String timeOfDay, String weekday) {
        String prompt = normalizeText(taskPrompt);
        return create(externalUserId, prompt, ReminderExecutionMode.AGENT, prompt,
                scheduleType, onceAt, timeOfDay, weekday);
    }

    private Reminder create(String externalUserId, String text, ReminderExecutionMode executionMode, String taskPrompt,
                            String scheduleType, String onceAt, String timeOfDay, String weekday) {
        String userId = require(externalUserId, "当前微信用户身份不可用，不能创建提醒");
        String message = normalizeText(text);
        ReminderScheduleType type = parseType(scheduleType);
        LocalDateTime once = type == ReminderScheduleType.ONCE ? parseDateTime(onceAt) : null;
        LocalTime time = type == ReminderScheduleType.ONCE ? null : parseTime(timeOfDay);
        DayOfWeek day = type == ReminderScheduleType.WEEKLY ? parseWeekday(weekday) : null;
        Instant now = Instant.now();
        Instant nextFireAt = ReminderSchedule.initialFire(type, once, time, day, properties.zone(), now);
        Reminder reminder = new Reminder(UUID.randomUUID().toString(), userId, message, executionMode, taskPrompt, type,
                properties.zone().getId(), time, day == null ? null : day.getValue(), nextFireAt,
                null, ReminderStatus.ACTIVE, now);
        return repository.create(reminder);
    }

    public List<Reminder> list(String externalUserId, int limit) {
        return repository.listForUser(require(externalUserId, "当前微信用户身份不可用，不能读取提醒"),
                Math.max(1, Math.min(limit, 50)));
    }

    public boolean cancel(String externalUserId, String reminderId) {
        String id = require(reminderId, "提醒编号不能为空");
        return repository.cancel(require(externalUserId, "当前微信用户身份不可用，不能取消提醒"), id);
    }

    /** Converts due schedule definitions into immutable delivery occurrences. */
    public int materializeDueOccurrences(Instant now) {
        List<Reminder> due = repository.activeDue(now, properties.getBatchSize());
        for (Reminder reminder : due) {
            repository.materializeDueReminder(reminder, ReminderSchedule.nextAfter(reminder, reminder.nextFireAt()));
        }
        return due.size();
    }

    public List<ReminderDelivery> claimDueDeliveries(Instant now) {
        return repository.claimDueDeliveries(now, properties.getBatchSize());
    }

    public int beginDeliveryAttempt(String deliveryId) { return repository.beginDeliveryAttempt(deliveryId); }
    public void markSent(String deliveryId, Instant now) { repository.markSent(deliveryId, now); }
    public void waitForContext(String deliveryId, Instant now) {
        repository.markWaitingForContext(deliveryId, now.plus(properties.getContextRetryInterval()));
    }
    public void retry(String deliveryId, int attempt, Instant now, String failure) {
        if (attempt >= properties.getMaxDeliveryAttempts()) {
            repository.markFailed(deliveryId, failure);
            return;
        }
        long delaySeconds = switch (Math.max(1, attempt)) {
            case 1 -> 10;
            case 2 -> 60;
            default -> 300;
        };
        repository.markRetry(deliveryId, now.plusSeconds(delaySeconds), failure);
    }
    public void recoverExpiredDeliveries(Instant now) {
        repository.recoverExpiredProcessing(now.minus(properties.getProcessingLease()));
    }

    /** A single-node process has no live workers after a restart, so reclaim all interrupted deliveries immediately. */
    public int recoverInterruptedDeliveriesOnStartup() {
        return repository.recoverInterruptedProcessingOnStartup();
    }

    private static ReminderScheduleType parseType(String value) {
        try {
            return ReminderScheduleType.valueOf(require(value, "提醒类型不能为空").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("提醒类型只能是 ONCE、DAILY 或 WEEKLY");
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        String input = require(value, "一次性提醒需要 onceAt，格式为 yyyy-MM-dd HH:mm");
        try {
            return LocalDateTime.parse(input, LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(input, LOCAL_DATE_TIME_SECONDS);
            } catch (DateTimeParseException failure) {
                throw new IllegalArgumentException("onceAt 格式应为 yyyy-MM-dd HH:mm，例如 2026-07-29 08:30");
            }
        }
    }

    private static LocalTime parseTime(String value) {
        String input = require(value, "重复提醒需要 timeOfDay，格式为 HH:mm");
        try {
            return LocalTime.parse(input);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("timeOfDay 格式应为 HH:mm，例如 08:30");
        }
    }

    private static DayOfWeek parseWeekday(String value) {
        String input = require(value, "每周提醒需要 weekday，例如 MONDAY");
        try {
            return DayOfWeek.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("weekday 只能是 MONDAY 到 SUNDAY");
        }
    }

    private static String normalizeText(String value) {
        String text = require(value, "提醒内容不能为空").replace('\u0000', ' ').strip();
        if (text.length() > 1000) throw new IllegalArgumentException("提醒内容不能超过 1000 个字符");
        return text;
    }

    private static String require(String value, String error) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(error);
        return value.strip();
    }
}
