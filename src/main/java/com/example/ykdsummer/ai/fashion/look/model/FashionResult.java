package com.example.ykdsummer.ai.fashion.look.model;

import java.util.List;

/**
 * 穿搭推荐管道的最终输出。由 AgentCoordinator 产出，交给 FashionResponseFormatter 格式化。
 *
 * @param success          管道是否完整执行（未降级）
 * @param coordinator      Coordinator 的最终裁决方案
 * @param stylist          Stylist 的原始方案列表
 * @param critic           Critic 的评审意见
 * @param trend            Trend 的趋势分析
 * @param ragContext       RAG 检索的知识片段摘要
 * @param analyzedQuery    查询分析结果
 * @param errorMessage     降级时的错误信息
 * @param degraded         是否发生了降级
 */
public record FashionResult(
        boolean success,
        CoordinatorOutput coordinator,
        StylistOutput stylist,
        CriticOutput critic,
        TrendOutput trend,
        String ragContext,
        AnalyzedQuery analyzedQuery,
        String errorMessage,
        boolean degraded
) {

    /** 构造降级结果。 */
    public static FashionResult degraded(String errorMessage, AnalyzedQuery query) {
        return new FashionResult(
                false, null, StylistOutput.empty(), CriticOutput.empty(),
                TrendOutput.neutral(), null, query, errorMessage, true
        );
    }

    /** 构造安全兜底结果（所有 Agent 全部失败时使用）。 */
    public static FashionResult safetyFallback(String scene, AnalyzedQuery query) {
        return new FashionResult(
                false, null, StylistOutput.empty(), CriticOutput.empty(),
                TrendOutput.neutral(), null, query,
                "所有 Agent 均不可用，返回安全兜底方案", true
        );
    }
}
