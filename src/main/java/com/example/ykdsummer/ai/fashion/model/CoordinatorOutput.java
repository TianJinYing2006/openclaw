package com.example.ykdsummer.ai.fashion.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Coordinator Agent 的输出：最终裁决方案。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoordinatorOutput(
        FinalRecommendation finalRecommendation,
        RefinedOutfit refinedOutfit,
        String finalReasoning,
        List<String> practicalTips
) {

    /**
     * 最终推荐决策。
     *
     * @param selectedSuggestionId 选中的 Stylist 方案 ID
     * @param selectionReasoning   选择理由
     * @param eliminationNotes     淘汰方案及原因
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FinalRecommendation(
            int selectedSuggestionId,
            String selectionReasoning,
            Map<String, String> eliminationNotes
    ) {}

    /**
     * 精炼后的最终穿搭方案（可能融合多套方案优点）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefinedOutfit(
            String top,
            String bottom,
            String shoes,
            String accessories
    ) {}
}
