package com.example.ykdsummer.schedule.model;

/**
 * 定时任务类型。
 *
 * <p>CRON：周期性 cron 表达式任务，由 cron_expr 定义执行计划。
 * ONCE：一次性定时任务，由 fire_at 指定执行时间。
 */
public enum TaskType {
    CRON,
    ONCE
}
