package com.example.ykdsummer.reminder.domain;

/** MESSAGE sends a fixed notification; AGENT executes the saved intent at its scheduled time. */
public enum ReminderExecutionMode {
    MESSAGE,
    AGENT
}
