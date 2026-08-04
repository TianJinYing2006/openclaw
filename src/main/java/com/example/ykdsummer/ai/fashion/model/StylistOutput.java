package com.example.ykdsummer.ai.fashion.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Stylist Agent 的输出：3 套穿搭方案。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StylistOutput(List<OutfitSuggestion> suggestions) {

    /** 空输出，用于 Stylist 失败时的降级。 */
    public static StylistOutput empty() {
        return new StylistOutput(List.of());
    }

    public boolean isEmpty() {
        return suggestions == null || suggestions.isEmpty();
    }

    /**
     * 单套穿搭方案。
     *
     * @param id             方案编号 1-3
     * @param styleLabel     风格标签，如 "优雅浪漫风"
     * @param outfit         穿搭单品
     * @param colorScheme    色彩方案描述
     * @param reasoning      选择理由
     * @param suitableFor    适用场景列表
     * @param bodyTypeNotes  体型适配说明
     * @param referenceOutfitId  该方案主要参考的 RAG 知识条目编号（如 "002"），用于图片补发对齐
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutfitSuggestion(
            int id,
            String styleLabel,
            Outfit outfit,
            String colorScheme,
            String reasoning,
            List<String> suitableFor,
            String bodyTypeNotes,
            String referenceOutfitId
    ) {}

    /** 穿搭单品。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Outfit(String top, String bottom, String shoes, String accessories) {}
}
