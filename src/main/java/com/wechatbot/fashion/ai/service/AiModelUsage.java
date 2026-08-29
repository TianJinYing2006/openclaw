package com.wechatbot.fashion.ai.service;

/** 模型网关在一次成功响应中报告的用量。 */
public record AiModelUsage(long promptTokens, long completionTokens, long totalTokens, boolean reported) {
    public AiModelUsage {
        promptTokens = Math.max(0L, promptTokens);
        completionTokens = Math.max(0L, completionTokens);
        totalTokens = Math.max(totalTokens, promptTokens + completionTokens);
    }
    public static AiModelUsage reported(long promptTokens, long completionTokens, long totalTokens) {
        return new AiModelUsage(promptTokens, completionTokens, totalTokens, true);
    }
    public static AiModelUsage unknown() { return new AiModelUsage(0L, 0L, 0L, false); }
}
