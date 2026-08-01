package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Adds consistent execution traces around every Spring AI Tool callback. */
final class TracingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AiTraceLogger trace;
    private final RealtimeEvidenceAugmenter realtimeEvidenceAugmenter;
    private final UsageEventRecorder usageEvents;

    TracingToolCallback(ToolCallback delegate, AiTraceLogger trace) {
        this(delegate, trace, null, UsageEventRecorder.disabled());
    }

    TracingToolCallback(
            ToolCallback delegate,
            AiTraceLogger trace,
            RealtimeEvidenceAugmenter realtimeEvidenceAugmenter
    ) {
        this(delegate, trace, realtimeEvidenceAugmenter, UsageEventRecorder.disabled());
    }

    TracingToolCallback(
            ToolCallback delegate,
            AiTraceLogger trace,
            RealtimeEvidenceAugmenter realtimeEvidenceAugmenter,
            UsageEventRecorder usageEvents
    ) {
        this.delegate = delegate;
        this.trace = trace;
        this.realtimeEvidenceAugmenter = realtimeEvidenceAugmenter;
        this.usageEvents = usageEvents == null ? UsageEventRecorder.disabled() : usageEvents;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return traceCall(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return traceCall(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String traceCall(String toolInput, Supplier<String> operation) {
        String toolName = getToolDefinition().name();
        long startedAt = System.nanoTime();
        trace.toolCall(toolName, toolInput);
        try {
            String result = realtimeEvidenceAugmenter == null
                    ? operation.get()
                    : realtimeEvidenceAugmenter.augment(toolName, toolInput, operation);
            long durationMs = elapsedMillis(startedAt);
            trace.toolResult(toolName, result, durationMs);
            usageEvents.recordTool(AgentSessionContext.currentUserId(), toolName, true, durationMs);
            return result;
        } catch (RuntimeException exception) {
            long durationMs = elapsedMillis(startedAt);
            trace.toolFailure(toolName, exception, durationMs);
            usageEvents.recordTool(AgentSessionContext.currentUserId(), toolName, false, durationMs);
            throw exception;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
