package com.wechatbot.fashion.ai.service;

/** Durable operational metrics are optional and never participate in request success/failure. */
public interface UsageEventRecorder {
    void recordModel(String userId, String protocol, String model, AiModelUsage usage, long estimatedTotalTokens);

    /** The duration is measured around the whole gateway invocation, including model tool orchestration. */
    default void recordModel(String userId, String protocol, String model, AiModelUsage usage,
                             long estimatedTotalTokens, long durationMs) {
        recordModel(userId, protocol, model, usage, estimatedTotalTokens);
    }

    /** Failed attempts are operational facts, but deliberately do not consume the user's token budget. */
    default void recordModelFailure(String userId, String protocol, String model, String failureReason, long durationMs) { }

    void recordOperation(String userId, String kind, String model, String toolName, long quantity, long durationMs);
    void recordTool(String userId, String toolName, boolean succeeded, long durationMs);

    static UsageEventRecorder disabled() {
        return new UsageEventRecorder() {
            @Override public void recordModel(String userId, String protocol, String model, AiModelUsage usage, long estimatedTotalTokens) { }
            @Override public void recordOperation(String userId, String kind, String model, String toolName, long quantity, long durationMs) { }
            @Override public void recordTool(String userId, String toolName, boolean succeeded, long durationMs) { }
        };
    }
}
