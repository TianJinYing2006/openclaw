package com.wechatbot.fashion.reminder.domain;

import java.time.Instant;
import java.time.LocalTime;

public record Reminder(
        String id,
        String externalUserId,
        String text,
        ReminderExecutionMode executionMode,
        String taskPrompt,
        ReminderScheduleType scheduleType,
        String zoneId,
        LocalTime localTime,
        Integer weekday,
        Instant nextFireAt,
        Instant lastFireAt,
        ReminderStatus status,
        Instant createdAt
) { }
