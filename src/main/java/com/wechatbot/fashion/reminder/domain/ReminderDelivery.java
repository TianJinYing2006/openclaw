package com.wechatbot.fashion.reminder.domain;

import java.time.Instant;

public record ReminderDelivery(
        String id,
        String reminderId,
        String externalUserId,
        Instant scheduledFor,
        String message,
        ReminderExecutionMode executionMode,
        String taskPrompt,
        ReminderDeliveryStatus status,
        int attemptCount
) { }
