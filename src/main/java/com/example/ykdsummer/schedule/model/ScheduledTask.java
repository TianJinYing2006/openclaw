package com.example.ykdsummer.schedule.model;

import java.time.LocalDateTime;

/**
 * 定时任务实体，对应 scheduled_tasks 表。
 *
 * <p>CRON 任务使用 cronExpr 周期性触发；
 * ONCE 任务使用 fireAt 指定一次性执行时间。
 */
public class ScheduledTask {

    private Long id;
    private String name;

    /** 归属的微信用户 ID（iLink fromUserId）；null 表示全局任务 */
    private String userId;

    private TaskType taskType;

    /** CRON 任务时：cron 表达式（6 字段，如 "0 0 8 * * ?"） */
    private String cronExpr;

    /** ONCE 任务时：目标执行时间 */
    private LocalDateTime fireAt;

    private TaskStatus status;

    /** 处理器 Spring Bean 名称，需实现 {@link com.example.ykdsummer.schedule.handler.ScheduledTaskHandler} */
    private String handlerBean;

    /** JSON 参数，传递给处理器 */
    private String params;

    /** misfire 策略：IGNORE / FIRE_NOW，默认 IGNORE */
    private String misfirePolicy;

    private LocalDateTime lastRunAt;
    private String lastRunResult;
    private LocalDateTime nextRunAt;
    private int totalRunCount;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ========== getters / setters ==========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public TaskType getTaskType() { return taskType; }
    public void setTaskType(TaskType taskType) { this.taskType = taskType; }

    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }

    public LocalDateTime getFireAt() { return fireAt; }
    public void setFireAt(LocalDateTime fireAt) { this.fireAt = fireAt; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public String getHandlerBean() { return handlerBean; }
    public void setHandlerBean(String handlerBean) { this.handlerBean = handlerBean; }

    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }

    public String getMisfirePolicy() { return misfirePolicy; }
    public void setMisfirePolicy(String misfirePolicy) { this.misfirePolicy = misfirePolicy; }

    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public String getLastRunResult() { return lastRunResult; }
    public void setLastRunResult(String lastRunResult) { this.lastRunResult = lastRunResult; }

    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

    public int getTotalRunCount() { return totalRunCount; }
    public void setTotalRunCount(int totalRunCount) { this.totalRunCount = totalRunCount; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "ScheduledTask{id=" + id + ", name='" + name + "', type=" + taskType
                + ", status=" + status + ", handler='" + handlerBean + "'}";
    }
}
