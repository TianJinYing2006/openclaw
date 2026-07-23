package com.example.ykdsummer.ai.service;

/** 一次模型请求的预算计划，由 {@link TokenBudgetPolicy} 在真正发 HTTP 前生成。 */
public record AiRequestBudget(TaskClass taskClass, int maxOutputTokens, long estimatedInputTokens, long reservedTokens) {
    public AiRequestBudget {
        taskClass = taskClass == null ? TaskClass.STANDARD : taskClass;
        maxOutputTokens = Math.max(1, maxOutputTokens);
        estimatedInputTokens = Math.max(0L, estimatedInputTokens);
        reservedTokens = Math.max(estimatedInputTokens, reservedTokens);
    }
    public enum TaskClass { SIMPLE_TEXT, STANDARD, COMPLEX_OR_MULTIMODAL }
}
