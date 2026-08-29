package com.example.ykdsummer.schedule.service;

import com.example.ykdsummer.schedule.handler.ScheduledTaskHandler;
import com.example.ykdsummer.schedule.model.ScheduledTask;
import com.example.ykdsummer.schedule.model.TaskStatus;
import com.example.ykdsummer.schedule.model.TaskType;
import com.example.ykdsummer.schedule.repo.ScheduledTaskRepository;
 import jakarta.annotation.PreDestroy;
 import org.springframework.boot.context.event.ApplicationReadyEvent;
 import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 动态定时任务调度器。
 *
 * <p>核心职责：
 * <ul>
 *   <li>应用启动时从 MySQL 恢复所有 WAITING 状态的任务</li>
 *   <li>提供创建、暂停、恢复、取消任务的 API</li>
 *   <li>通过 {@link ScheduledTaskHandler} 接口分发任务执行</li>
 *   <li>应用关闭时优雅停止（取消待触发任务，等待执行中的任务完成）</li>
 * </ul>
 *
 * <p>CRON 任务使用 {@link CronTrigger}（6 字段），时区固定为 Asia/Shanghai。
 * ONCE 任务使用 {@link ThreadPoolTaskScheduler#schedule(Runnable, Instant)} 单次触发。
 */
@Service
public class DynamicTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(DynamicTaskScheduler.class);

    /** 东八区时区 */
    private static final TimeZone TIMEZONE = TimeZone.getTimeZone("Asia/Shanghai");
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /** 已注册到调度器的任务，key = taskId */
    private final Map<Long, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    /** 正在执行的任务，value 用于防止同一任务并发执行 */
    private final Map<Long, AtomicBoolean> runningTasks = new ConcurrentHashMap<>();

    private final ThreadPoolTaskScheduler taskScheduler;
    private final ScheduledTaskRepository taskRepository;
    private final ApplicationContext applicationContext;

    public DynamicTaskScheduler(ThreadPoolTaskScheduler taskScheduler,
                                ScheduledTaskRepository taskRepository,
                                ApplicationContext applicationContext) {
        this.taskScheduler = taskScheduler;
        this.taskRepository = taskRepository;
        this.applicationContext = applicationContext;
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * 应用启动时初始化。
     *
     * <ol>
     *   <li>将上次异常退出残留的 RUNNING 任务重置为 WAITING</li>
     *   <li>加载所有 WAITING 状态的任务，注册到调度器</li>
     * </ol>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("DynamicTaskScheduler initializing...");

        // 1. 重置异常残留的任务
        taskRepository.resetRunningTasks();

        // 2. 加载 WAITING 任务并注册
        List<ScheduledTask> tasks = taskRepository.findByStatus(TaskStatus.WAITING);
        int scheduled = 0;
        int skipped = 0;
        for (ScheduledTask task : tasks) {
            // ONCE 任务若执行时间已过则跳过（进程重启导致）
            if (task.getTaskType() == TaskType.ONCE
                    && task.getFireAt() != null
                    && task.getFireAt().isBefore(LocalDateTime.now())) {
                log.warn("Skipping past ONCE task: id={}, name='{}', fireAt={}",
                        task.getId(), task.getName(), task.getFireAt());
                taskRepository.recordRun(task.getId(), TaskStatus.FAILED,
                        "MISSED: fire time was in the past on restart");
                skipped++;
                continue;
            }

            try {
                scheduleTask(task);
                scheduled++;
            } catch (Exception e) {
                log.error("Failed to schedule task id={} name='{}'", task.getId(), task.getName(), e);
            }
        }
        log.info("DynamicTaskScheduler initialized: restored {} of {} tasks", scheduled, tasks.size());
        if (skipped > 0) {
            log.warn("{} ONCE task(s) skipped due to past fire time", skipped);
        }
    }

    /**
     * 应用关闭时优雅停止。
     *
     * <ol>
     *   <li>取消所有待触发的 ScheduledFuture（不让新任务启动）</li>
     *   <li>已在执行中的任务不受影响，由 {@code ThreadPoolTaskScheduler} 的
     *       {@code setWaitForTasksToCompleteOnShutdown(true)} 等待其完成</li>
     * </ol>
     */
    @PreDestroy
    public void destroy() {
        log.info("DynamicTaskScheduler shutting down gracefully...");
        int active = activeFutures.size();
        int running = runningTasks.size();

        for (Map.Entry<Long, ScheduledFuture<?>> entry : activeFutures.entrySet()) {
            entry.getValue().cancel(false); // mayInterruptIfRunning = false
        }
        activeFutures.clear();

        if (active > 0 || running > 0) {
            log.info("Cancelled {} pending triggers, {} tasks still running (will wait up to 30s)", active, running);
        }
    }

    // ==================================================================
    // 任务管理 API
    // ==================================================================

    /**
     * 创建并调度一个定时任务。
     *
     * @param name         任务名称
     * @param userId       归属用户 ID
     * @param taskType     任务类型（CRON / ONCE）
     * @param cronExpr     CRON 表达式（taskType=CRON 时必填）
     * @param fireAt       执行时间（taskType=ONCE 时必填）
     * @param handlerBean  处理器 Spring Bean 名称
     * @param params       JSON 参数字符串
     * @return 创建后的任务实体
     */
    public ScheduledTask createTask(String name, String userId, TaskType taskType, String cronExpr,
                                    LocalDateTime fireAt, String handlerBean, String params) {
        // 校验
        if (taskType == TaskType.CRON && (cronExpr == null || cronExpr.isBlank())) {
            throw new IllegalArgumentException("CRON task requires a cron expression");
        }
        if (taskType == TaskType.ONCE && fireAt == null) {
            throw new IllegalArgumentException("ONCE task requires a fireAt time");
        }

        // ONCE 任务如果时间已过，拒绝创建
        if (taskType == TaskType.ONCE && fireAt.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("fireAt must be in the future for ONCE task");
        }

        // 构建实体
        ScheduledTask task = new ScheduledTask();
        task.setName(name);
        task.setUserId(userId);
        task.setTaskType(taskType);
        task.setCronExpr(cronExpr != null ? cronExpr : "");
        task.setFireAt(fireAt);
        task.setStatus(TaskStatus.WAITING);
        task.setHandlerBean(handlerBean);
        task.setParams(params != null ? params : "{}");
        task.setMisfirePolicy("IGNORE");

        // 写入 DB
        long id = taskRepository.insert(task);
        task.setId(id);

        // 注册到调度器
        scheduleTask(task);
        log.info("Task created and scheduled: id={}, name='{}', type={}, cron='{}', fireAt={}",
                id, name, taskType, cronExpr, fireAt);

        return task;
    }

    /**
     * 取消一个定时任务（从调度器移除 + DB 删除）。
     */
    public void cancelTask(Long taskId) {
        cancelFuture(taskId);
        taskRepository.deleteById(taskId);
        runningTasks.remove(taskId);
        log.info("Task cancelled: id={}", taskId);
    }

    /**
     * 暂停一个定时任务（从调度器移除，保留 DB 记录，状态置为 PAUSED）。
     */
    public void pauseTask(Long taskId) {
        cancelFuture(taskId);
        taskRepository.pauseTask(taskId);
        log.info("Task paused: id={}", taskId);
    }

    /**
     * 恢复一个已暂停的任务（重新注册到调度器，状态置为 WAITING）。
     */
    public void resumeTask(Long taskId) {
        Optional<ScheduledTask> opt = taskRepository.findById(taskId);
        if (opt.isEmpty()) {
            log.warn("Cannot resume: task not found, id={}", taskId);
            return;
        }
        ScheduledTask task = opt.get();
        if (task.getStatus() != TaskStatus.PAUSED) {
            log.warn("Cannot resume: task is not PAUSED (current={}), id={}", task.getStatus(), taskId);
            return;
        }

        taskRepository.resumeTask(taskId);
        task.setStatus(TaskStatus.WAITING);
        scheduleTask(task);
        log.info("Task resumed: id={}, name='{}'", taskId, task.getName());
    }

    /**
     * 获取所有任务（含 WAITING / RUNNING / PAUSED / FINISHED / FAILED）。
     */
    public List<ScheduledTask> listAllTasks() {
        return taskRepository.findAll();
    }

    /**
     * 按 ID 获取任务详情。
     */
    public Optional<ScheduledTask> getTask(Long taskId) {
        return taskRepository.findById(taskId);
    }

    // ==================================================================
    // 内部调度
    // ==================================================================

    /** 注册任务到 ThreadPoolTaskScheduler */
    private void scheduleTask(ScheduledTask task) {
        // 如果已经注册，先取消旧的
        cancelFuture(task.getId());

        Runnable runnable = () -> executeTask(task.getId());
        ScheduledFuture<?> future;

        if (task.getTaskType() == TaskType.CRON) {
            future = taskScheduler.schedule(runnable, new CronTrigger(task.getCronExpr(), TIMEZONE));
        } else {
            Instant triggerTime = task.getFireAt().atZone(ZONE_ID).toInstant();
            future = taskScheduler.schedule(runnable, triggerTime);
        }

        activeFutures.put(task.getId(), future);
    }

    /** 取消已注册的 ScheduledFuture */
    private void cancelFuture(Long taskId) {
        ScheduledFuture<?> existing = activeFutures.remove(taskId);
        if (existing != null) {
            existing.cancel(false); // 不中断正在执行的任务
        }
    }

    /**
     * 任务触发时执行（在 ThreadPoolTaskScheduler 的线程中运行）。
     *
     * <p>每次触发时重新从 DB 获取最新状态，确保任务没有被暂停或删除。
     * 同一时间同一任务只会执行一次（并发执行检查）。
     */
    private void executeTask(Long taskId) {
        // 防止同一任务并发执行
        AtomicBoolean flag = runningTasks.computeIfAbsent(taskId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            log.warn("Task {} is already running, skipped (concurrent fire)", taskId);
            return;
        }

        try {
            // 重新从 DB 获取最新状态
            Optional<ScheduledTask> opt = taskRepository.findById(taskId);
            if (opt.isEmpty()) {
                log.warn("Task {} not found in DB, cancelling", taskId);
                cancelFuture(taskId);
                return;
            }

            ScheduledTask task = opt.get();

            // 检查状态：只有 WAITING 的任务才能执行（防止暂停/删除后仍被旧 future 触发）
            if (task.getStatus() != TaskStatus.WAITING) {
                log.debug("Task {} status is {}, skipping execution", taskId, task.getStatus());
                cancelFuture(taskId); // 清理失效的 future
                return;
            }

            // 标记为 RUNNING
            taskRepository.updateStatus(taskId, TaskStatus.RUNNING);
            log.debug("Task {} ('{}') started", taskId, task.getName());

            // 执行处理器
            ScheduledTaskHandler handler = applicationContext.getBean(task.getHandlerBean(), ScheduledTaskHandler.class);
            handler.execute(task);

            // 执行成功：更新状态
            if (task.getTaskType() == TaskType.ONCE) {
                taskRepository.markFinished(taskId);
                activeFutures.remove(taskId); // 一次性任务 future 已失效
            } else {
                taskRepository.recordRun(taskId, TaskStatus.WAITING, "SUCCESS");
            }
            log.info("Task {} ('{}') completed successfully", taskId, task.getName());

        } catch (Exception e) {
            log.error("Task {} execution failed", taskId, e);
            try {
                taskRepository.recordRun(taskId, TaskStatus.FAILED, "FAILED: " + e.getMessage());
            } catch (Exception dbEx) {
                log.error("Failed to update task {} status after error", taskId, dbEx);
            }
        } finally {
            runningTasks.remove(taskId);
        }
    }
}
