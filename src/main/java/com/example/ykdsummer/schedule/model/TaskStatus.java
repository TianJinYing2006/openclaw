package com.example.ykdsummer.schedule.model;

/**
 * 定时任务运行状态。
 *
 * <p>WAITING  — 待执行（已注册到调度器，等待触发）。
 * RUNNING   — 正在执行。
 * PAUSED    — 已暂停（从调度器移除，保留定义）。
 * FINISHED  — 已完成（一次性任务执行成功）。
 * FAILED    — 执行失败。
 */
public enum TaskStatus {
    WAITING,
    RUNNING,
    PAUSED,
    FINISHED,
    FAILED
}
