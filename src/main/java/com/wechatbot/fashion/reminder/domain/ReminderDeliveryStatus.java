package com.wechatbot.fashion.reminder.domain;

public enum ReminderDeliveryStatus {
    PENDING,
    PROCESSING,
    WAITING_CONTEXT,
    RETRY,
    SENT,
    FAILED
}
