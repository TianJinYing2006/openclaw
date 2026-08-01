package com.example.ykdsummer.reminder.tool;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.reminder.application.ReminderService;
import com.example.ykdsummer.reminder.domain.Reminder;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Agent boundary: all reminder operations are scoped to the current WeChat user. */
@Component
@ConditionalOnBean(ReminderService.class)
public class ReminderTools implements AiTool {
    private static final java.time.ZoneId DISPLAY_ZONE = java.time.ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final ReminderService reminders;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public ReminderTools(ReminderService reminders, ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this.reminders = reminders;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "create_scheduled_agent_task", description = "当用户明确要求设置微信提醒、闹钟或未来定时任务时调用。"
            + "所有定时任务到点后都会重新唤起 Agent；它会自行决定直接发送提醒，或调用天气、联网、地图、图片等工具完成任务。"
            + "例如“10 分钟后提醒我喝水”“10 分钟后查杭州天气”“每天早上帮我总结新闻”。"
            + "只在时间明确时调用；相对时间必须先调用 get_current_china_time。"
            + "scheduleType 只能是 ONCE、DAILY、WEEKLY。ONCE 必须传 onceAt，格式 yyyy-MM-dd HH:mm（中国时区）；"
            + "DAILY 必须传 timeOfDay，格式 HH:mm；WEEKLY 还必须传 weekday，使用 MONDAY 到 SUNDAY。")
    public String createScheduledAgentTask(
            @ToolParam(description = "到点后要执行的原始意图或提醒内容，例如 记得提交周报、查询杭州天气并给出出行建议") String taskPrompt,
            @ToolParam(description = "ONCE、DAILY 或 WEEKLY") String scheduleType,
            @ToolParam(required = false, description = "仅 ONCE 需要，格式 yyyy-MM-dd HH:mm，例如 2026-07-29 08:30") String onceAt,
            @ToolParam(required = false, description = "DAILY/WEEKLY 需要，格式 HH:mm，例如 08:30") String timeOfDay,
            @ToolParam(required = false, description = "仅 WEEKLY 需要：MONDAY 到 SUNDAY") String weekday
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("create_scheduled_agent_task", "scheduleType=" + safe(scheduleType));
        try {
            Reminder task = reminders.createAgentTask(userId, taskPrompt, scheduleType, onceAt, timeOfDay, weekday);
            String result = "已创建定时 Agent 任务 #" + shortId(task.id()) + "：" + task.text()
                    + "。到点后 Agent 会自行完成并把结果发到微信。下次执行时间：" + format(task) + "。";
            trace.toolResult("create_scheduled_agent_task", result);
            return result;
        } catch (IllegalArgumentException failure) {
            return failed("create_scheduled_agent_task", failure, "创建定时任务失败：" + failure.getMessage());
        } catch (RuntimeException failure) {
            return failed("create_scheduled_agent_task", failure, "创建定时任务失败，请稍后重试。");
        }
    }

    @Tool(name = "list_wechat_reminders", description = "当用户询问已设置的提醒、闹钟、下一次提醒是什么时调用。"
            + "只能查看当前微信用户自己的提醒。")
    public String listWechatReminders() {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("list_wechat_reminders", "current user");
        try {
            List<Reminder> values = reminders.list(userId, 20);
            String result = values.isEmpty() ? "当前没有已保存的微信提醒。" : describe(values);
            trace.toolResult("list_wechat_reminders", result);
            return result;
        } catch (RuntimeException failure) {
            return failed("list_wechat_reminders", failure, "读取提醒失败，请稍后重试。");
        }
    }

    @Tool(name = "cancel_wechat_reminder", description = "当用户明确要求取消、删除某个已设置的微信提醒时调用。"
            + "reminderId 必须来自 list_wechat_reminders 返回的完整编号；不可猜测或取消其他用户的提醒。")
    public String cancelWechatReminder(@ToolParam(description = "完整提醒编号 UUID") String reminderId) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("cancel_wechat_reminder", "reminderId=" + shortId(safe(reminderId)));
        try {
            boolean cancelled = reminders.cancel(userId, reminderId);
            String result = cancelled ? "已取消提醒 #" + shortId(reminderId) + "。" : "没有找到可取消的该提醒编号。";
            trace.toolResult("cancel_wechat_reminder", result);
            return result;
        } catch (IllegalArgumentException failure) {
            return failed("cancel_wechat_reminder", failure, "取消提醒失败：" + failure.getMessage());
        } catch (RuntimeException failure) {
            return failed("cancel_wechat_reminder", failure, "取消提醒失败，请稍后重试。");
        }
    }

    private String currentUser() {
        String userId = artifacts.userId();
        return userId == null || userId.isBlank() || "unknown".equals(userId) ? null : userId;
    }

    private String failed(String toolName, RuntimeException failure, String message) {
        trace.toolFailure(toolName, failure);
        return message;
    }

    private static String unavailable() { return "当前会话身份不可用，暂时不能管理微信提醒。"; }
    private static String describe(List<Reminder> reminders) {
        StringBuilder result = new StringBuilder("当前微信提醒：\n");
        for (Reminder reminder : reminders) {
            result.append("- #").append(reminder.id()).append(" | ").append(reminder.status())
                    .append(" | ").append(reminder.executionMode() == com.example.ykdsummer.reminder.domain.ReminderExecutionMode.AGENT
                            ? "定时任务" : "文字提醒")
                    .append(" | ").append(reminder.text());
            if (reminder.nextFireAt() != null) result.append(" | 下次 ").append(format(reminder));
            result.append('\n');
        }
        return result.toString().strip();
    }
    private static String format(Reminder reminder) {
        return reminder.nextFireAt().atZone(DISPLAY_ZONE).format(DATE_TIME) + "（" + recurrence(reminder) + "）";
    }
    private static String recurrence(Reminder reminder) {
        return switch (reminder.scheduleType()) {
            case ONCE -> "一次";
            case DAILY -> "每天";
            case WEEKLY -> "每周" + DayOfWeek.of(reminder.weekday()).name();
        };
    }
    private static String shortId(String id) { return id == null ? "" : id.length() <= 8 ? id : id.substring(0, 8); }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
