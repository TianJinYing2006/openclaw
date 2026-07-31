package com.example.ykdsummer.ai.fashion.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Trend Agent 的输出：对每套方案的趋势分析。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrendOutput(List<TrendReview> trendAnalysis) {

    /** 中性趋势，用于 Trend 失败时的降级（trend_score=3.0）。 */
    public static TrendOutput neutral() {
        return new TrendOutput(List.of());
    }

    public boolean isEmpty() {
        return trendAnalysis == null || trendAnalysis.isEmpty();
    }

    /**
     * 对单套方案的趋势评审。
     *
     * @param suggestionId    对应 Stylist 输出的方案 ID
     * @param trendScore      趋势匹配分 1-5
     * @param seasonalMatch   季节匹配描述
     * @param trendingElements 流行元素列表
     * @param datedElements    过时元素列表
     * @param searchSummary    趋势搜索摘要
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrendReview(
            int suggestionId,
            int trendScore,
            String seasonalMatch,
            List<String> trendingElements,
            List<String> datedElements,
            String searchSummary
    ) {}
}
