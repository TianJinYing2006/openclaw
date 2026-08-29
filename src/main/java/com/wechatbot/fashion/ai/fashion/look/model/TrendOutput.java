package com.wechatbot.fashion.ai.fashion.look.model;

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

    /** 针对已有 Stylist 方案生成中性趋势评审，用于 Trend 调用失败或结果缺项时的降级。 */
    public static TrendOutput neutralFor(StylistOutput stylist) {
        if (stylist == null || stylist.isEmpty()) {
            return neutral();
        }
        return new TrendOutput(stylist.suggestions().stream()
                .map(suggestion -> neutralReview(suggestion.id()))
                .toList());
    }

    public static TrendReview neutralReview(int suggestionId) {
        return new TrendReview(
                suggestionId,
                3,
                "基本匹配",
                List.of("基础流行元素待确认"),
                List.of("无明显过时元素"),
                "趋势分析不可用，按中性趋势分处理。"
        );
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
