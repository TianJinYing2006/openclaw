package com.example.ykdsummer.reminder.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.reminder.application.ReminderService;
import com.example.ykdsummer.reminder.domain.Reminder;
import com.example.ykdsummer.reminder.domain.ReminderExecutionMode;
import com.example.ykdsummer.reminder.domain.ReminderScheduleType;
import com.example.ykdsummer.reminder.domain.ReminderStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class ReminderToolsCallbackTest {

    @Test
    void modelExposesOnlyTheAgentSchedulingEntryPointAndKeepsUserScope() {
        ReminderService service = mock(ReminderService.class);
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("managed:instance:wechat-user");
        Reminder reminder = new Reminder("01f1de07-2e47-4c27-8436-0820d5f6f746", "managed:instance:wechat-user",
                "记得开会", ReminderExecutionMode.AGENT, "记得开会", ReminderScheduleType.ONCE, "Asia/Shanghai", null, null,
                Instant.parse("2026-07-29T00:30:00Z"), null, ReminderStatus.ACTIVE, Instant.now());
        when(service.createAgentTask(eq("managed:instance:wechat-user"), eq("记得开会"), eq("ONCE"),
                eq("2026-07-29 08:30"), eq(""), eq(""))).thenReturn(reminder);
        ReminderTools tools = new ReminderTools(service, artifacts, AiTraceLogger.disabled());

        String created = callback(tools, "create_scheduled_agent_task").call("""
                {"taskPrompt":"记得开会","scheduleType":"ONCE","onceAt":"2026-07-29 08:30","timeOfDay":"","weekday":""}
                """);

        assertThat(created).contains("已创建定时 Agent 任务", "记得开会", "2026-07-29 08:30");
        assertThat(java.util.Arrays.stream(ToolCallbacks.from(tools))
                .map(callback -> callback.getToolDefinition().name()))
                .contains("create_scheduled_agent_task")
                .doesNotContain("create_wechat_reminder", "get_current_china_time");
        verify(service).createAgentTask("managed:instance:wechat-user", "记得开会", "ONCE", "2026-07-29 08:30", "", "");
        artifacts.finish();
    }

    @Test
    void chinaTimeToolRemainsAvailableOutsideReminderManagement() {
        String currentTime = callback(new ChinaTimeTools(AiTraceLogger.disabled()), "get_current_china_time").call("{}");
        assertThat(currentTime).contains("中国当前时间", "CST");
    }

    @Test
    void modelCanCreateAnAgentTaskThatPreservesTheOriginalTaskIntent() {
        ReminderService service = mock(ReminderService.class);
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("managed:instance:wechat-user");
        Reminder task = new Reminder("17f1de07-2e47-4c27-8436-0820d5f6f746", "managed:instance:wechat-user",
                "查询杭州天气", ReminderExecutionMode.AGENT, "查询杭州天气", ReminderScheduleType.ONCE,
                "Asia/Shanghai", null, null, Instant.parse("2026-07-29T00:30:00Z"), null,
                ReminderStatus.ACTIVE, Instant.now());
        when(service.createAgentTask(eq("managed:instance:wechat-user"), eq("查询杭州天气"), eq("ONCE"),
                eq("2026-07-29 08:30"), eq(""), eq(""))).thenReturn(task);
        ReminderTools tools = new ReminderTools(service, artifacts, AiTraceLogger.disabled());

        String created = callback(tools, "create_scheduled_agent_task").call("""
                {"taskPrompt":"查询杭州天气","scheduleType":"ONCE","onceAt":"2026-07-29 08:30","timeOfDay":"","weekday":""}
                """);

        assertThat(created).contains("已创建定时 Agent 任务", "查询杭州天气", "Agent 会自行完成");
        verify(service).createAgentTask("managed:instance:wechat-user", "查询杭州天气", "ONCE",
                "2026-07-29 08:30", "", "");
        artifacts.finish();
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
