package com.wechatbot.fashion.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechatbot.fashion.ai.orchestration.AgentSessionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class TracingToolCallbackTest {

    @Test
    void recordsToolArgumentsResultAndDuration() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        AiTraceLogger trace = mock(AiTraceLogger.class);
        UsageEventRecorder usage = mock(UsageEventRecorder.class);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(definition.name()).thenReturn("search_web");
        when(delegate.call("{\"query\":\"汇率\"}")).thenReturn("结果");

        AgentSessionContext.set("managed:instance-1:wechat-user", "chat");
        String result;
        try {
            result = new TracingToolCallback(delegate, trace, null, usage).call("{\"query\":\"汇率\"}");
        } finally {
            AgentSessionContext.clear();
        }

        assertThat(result).isEqualTo("结果");
        verify(trace).toolCall("search_web", "{\"query\":\"汇率\"}");
        verify(trace).toolResult(eq("search_web"), eq("结果"), anyLong());
        verify(usage).recordTool(eq("managed:instance-1:wechat-user"), eq("search_web"), eq(true), anyLong());
    }

    @Test
    void recordsToolFailuresBeforePropagatingThem() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        AiTraceLogger trace = mock(AiTraceLogger.class);
        UsageEventRecorder usage = mock(UsageEventRecorder.class);
        IllegalStateException failure = new IllegalStateException("offline");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(definition.name()).thenReturn("search_web");
        when(delegate.call("{}")).thenThrow(failure);

        AgentSessionContext.set("managed:instance-2:wechat-user", "chat");
        try {
            assertThatThrownBy(() -> new TracingToolCallback(delegate, trace, null, usage).call("{}"))
                    .isSameAs(failure);
        } finally {
            AgentSessionContext.clear();
        }

        verify(trace).toolFailure(eq("search_web"), eq(failure), anyLong());
        verify(usage).recordTool(eq("managed:instance-2:wechat-user"), eq("search_web"), eq(false), anyLong());
    }
}
