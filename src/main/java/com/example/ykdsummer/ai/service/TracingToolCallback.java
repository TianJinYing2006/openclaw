package com.example.ykdsummer.ai.service;

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

    TracingToolCallback(ToolCallback delegate, AiTraceLogger trace) {
        this(delegate, trace, null);
    }

    TracingToolCallback(
            ToolCallback delegate,
            AiTraceLogger trace,
            RealtimeEvidenceAugmenter realtimeEvidenceAugmenter
    ) {
        this.delegate = delegate;
        this.trace = trace;
        this.realtimeEvidenceAugmenter = realtimeEvidenceAugmenter;
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
            trace.toolResult(toolName, result, elapsedMillis(startedAt));
            return result;
        } catch (RuntimeException exception) {
            trace.toolFailure(toolName, exception, elapsedMillis(startedAt));
            throw exception;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
