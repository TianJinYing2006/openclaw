package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import com.example.ykdsummer.schedule.model.ScheduledTask;
import com.example.ykdsummer.schedule.model.TaskStatus;
import com.example.ykdsummer.schedule.model.TaskType;
import com.example.ykdsummer.schedule.repo.ScheduledTaskRepository;
import com.example.ykdsummer.schedule.service.DynamicTaskScheduler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 定时任务管理工具，供 AI 模型通过对话创建/查询/取消提醒。
 *
 * <p>依赖 {@link DynamicTaskScheduler} 调度任务，依赖 {@link AgentSessionContext}
 * 获取当前用户上下文。</p>
 */
@Component
public class ScheduleTaskTools {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTaskTools.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DynamicTaskScheduler taskScheduler;
    private final ScheduledTaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public ScheduleTaskTools(DynamicTaskScheduler taskScheduler,
                             ScheduledTaskRepository taskRepository,
                             ObjectMapper objectMapper) {
        this.taskScheduler = taskScheduler;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一个一次性定时提醒。时间格式为 "yyyy-MM-dd HH:mm"（24 小时制），
     * 例如 "2026-07-28 14:30" 表示 2026 年 7 月 28 日下午 2 点 30 分。
     *
     * @param time  提醒执行时间，格式 yyyy-MM-dd HH:mm
     * @param text  提醒内容
     * @return 创建结果描述
     */
    @Tool(description = "创建一次性定时提醒。时间格式 yyyy-MM-dd HH:mm（24 小时制），例如 2026-07-28 14:30。返回任务 ID 和预计执行时间。")
    public String create_reminder(String time, String text) {
        LocalDateTime fireAt;
        try {
            fireAt = LocalDateTime.parse(time, DT_FMT);
        } catch (DateTimeParseException e) {
            return "时间格式错误，请使用 yyyy-MM-dd HH:mm 格式，例如 2026-07-28 14:30";
        }

        if (fireAt.isBefore(LocalDateTime.now())) {
            return "提醒时间必须在当前时间之后，不能创建过去的提醒";
        }

        if (text == null || text.isBlank()) {
            return "提醒内容不能为空";
        }

        String userId = AgentSessionContext.currentUserId();
        String contextToken = AgentSessionContext.currentContextToken();
        if ("anonymous".equals(userId)) {
            return "无法获取用户信息，请稍后重试";
        }

        // 把 userId 和 contextToken 存入 params，供触发时发送消息使用
        String paramsJson;
        try {
            paramsJson = objectMapper.writeValueAsString(Map.of(
                    "toUserId", userId,
                    "contextToken", contextToken != null ? contextToken : "",
                    "text", text
            ));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize reminder params", e);
            return "创建提醒失败，请稍后重试";
        }

        try {
            ScheduledTask task = taskScheduler.createTask(
                    "提醒: " + truncate(text, 40),
                    userId,
                    TaskType.ONCE,
                    null,
                    fireAt,
                    "reminderTaskHandler",
                    paramsJson
            );
            log.info("Reminder created: id={}, userId={}, fireAt={}, text='{}'",
                    task.getId(), anonymize(userId), fireAt, truncate(text, 60));
            return "已创建提醒，编号 " + task.getId() + "，将在 "
                    + fireAt.format(DT_FMT) + " 提醒你：" + text;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.warn("Failed to create reminder", e);
            return "创建提醒失败，请稍后重试";
        }
    }

    /**
     * 列出当前用户的所有定时提醒（含待执行、暂停、已完成和失败的）。
     *
     * @return 提醒列表文字描述
     */
    @Tool(description = "列出当前用户的所有定时提醒，包括待执行、已完成和失败的。")
    public String list_my_reminders() {
        String userId = AgentSessionContext.currentUserId();
        if ("anonymous".equals(userId)) {
            return "无法获取用户信息";
        }

        List<ScheduledTask> tasks = taskRepository.findByUserId(userId);
        if (tasks.isEmpty()) {
            return "你目前没有任何定时提醒";
        }

        return tasks.stream()
                .map(t -> {
                    String statusIcon = switch (t.getStatus()) {
                        case WAITING -> "⏳";
                        case RUNNING -> "▶";
                        case PAUSED -> "⏸";
                        case FINISHED -> "✅";
                        case FAILED -> "❌";
                    };
                    return statusIcon + " #" + t.getId() + " "
                            + (t.getFireAt() != null ? t.getFireAt().format(DT_FMT) : "?")
                            + " 「" + extractTextFromParams(t.getParams()) + "」"
                            + " [" + t.getStatus() + "]";
                })
                .collect(Collectors.joining("\n", "你共有 " + tasks.size() + " 条提醒：\n", ""));
    }

    /**
     * 取消一个定时提醒（按任务编号）。
     *
     * @param taskId 要取消的任务编号
     * @return 取消结果描述
     */
    @Tool(description = "取消指定编号的定时提醒。任务编号来自 create_reminder 或 list_my_reminders 的返回结果。")
    public String cancel_reminder(Long taskId) {
        String userId = AgentSessionContext.currentUserId();
        if ("anonymous".equals(userId)) {
            return "无法获取用户信息";
        }

        var opt = taskRepository.findById(taskId);
        if (opt.isEmpty()) {
            return "未找到编号为 " + taskId + " 的提醒";
        }

        ScheduledTask task = opt.get();
        // 只允许取消自己的任务
        if (task.getUserId() != null && !task.getUserId().equals(userId)) {
            return "无权取消他人提醒";
        }

        if (task.getStatus() == TaskStatus.FINISHED) {
            return "提醒 #" + taskId + " 已经执行完成，无需取消";
        }
        if (task.getStatus() == TaskStatus.FAILED) {
            return "提醒 #" + taskId + " 已执行失败，无需取消";
        }

        taskScheduler.cancelTask(taskId);
        log.info("Reminder cancelled: id={}, userId={}", taskId, anonymize(userId));
        String text = extractTextFromParams(task.getParams());
        return "已取消提醒 #" + taskId + (text.isBlank() ? "" : "：「" + text + "」");
    }

    private static String extractTextFromParams(String params) {
        if (params == null || params.isBlank() || "{}".equals(params)) {
            return "";
        }
        try {
            var map = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(params, Map.class);
            Object text = map.get("text");
            return text != null ? text.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    private static String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen
                ? text.substring(0, maxLen) + "…"
                : text;
    }
}
