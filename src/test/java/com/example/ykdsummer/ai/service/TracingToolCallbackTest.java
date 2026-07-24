package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class TracingToolCallbackTest {

    @Test
    void recordsToolArgumentsResultAndDuration() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        AiTraceLogger trace = mock(AiTraceLogger.class);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(definition.name()).thenReturn("search_web");
        when(delegate.call("{\"query\":\"汇率\"}")).thenReturn("结果");

        String result = new TracingToolCallback(delegate, trace).call("{\"query\":\"汇率\"}");

        assertThat(result).isEqualTo("结果");
        verify(trace).toolCall("search_web", "{\"query\":\"汇率\"}");
        verify(trace).toolResult(eq("search_web"), eq("结果"), anyLong());
    }

    @Test
    void recordsToolFailuresBeforePropagatingThem() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        AiTraceLogger trace = mock(AiTraceLogger.class);
        IllegalStateException failure = new IllegalStateException("offline");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(definition.name()).thenReturn("search_web");
        when(delegate.call("{}")).thenThrow(failure);

        assertThatThrownBy(() -> new TracingToolCallback(delegate, trace).call("{}"))
                .isSameAs(failure);

        verify(trace).toolFailure(eq("search_web"), eq(failure), anyLong());
    }
}
