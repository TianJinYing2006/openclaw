package com.wechatbot.fashion.reminder.domain;

/** MESSAGE sends a fixed notification; AGENT executes the saved intent at its scheduled time. */
public enum ReminderExecutionMode {
    MESSAGE,
    AGENT
}
