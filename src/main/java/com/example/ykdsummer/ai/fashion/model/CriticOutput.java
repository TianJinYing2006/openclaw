package com.example.ykdsummer.ai.fashion.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Critic Agent 的输出：对每套方案的评审意见。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CriticOutput(List<Critique> reviews) {

    /** 空评审，用于 Critic 失败时的降级。 */
    public static CriticOutput empty() {
        return new CriticOutput(List.of());
    }

    public boolean isEmpty() {
        return reviews == null || reviews.isEmpty();
    }

    /**
     * 对单套方案的评审。
     *
     * @param suggestionId    对应 Stylist 输出的方案 ID
     * @param overallScore    综合评分 1-5
     * @param dimensionScores 各维度评分
     * @param strengths       优点列表
     * @param weaknesses      不足列表
     * @param improvements    改进建议列表
     * @param riskFlags       风险提示列表
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Critique(
            int suggestionId,
            int overallScore,
            Map<String, Integer> dimensionScores,
            List<String> strengths,
            List<String> weaknesses,
            List<String> improvements,
            List<String> riskFlags
    ) {}
}
