package com.example.ykdsummer.reminder.domain;

public enum ReminderDeliveryStatus {
    PENDING,
    PROCESSING,
    WAITING_CONTEXT,
    RETRY,
    SENT,
    FAILED
}
