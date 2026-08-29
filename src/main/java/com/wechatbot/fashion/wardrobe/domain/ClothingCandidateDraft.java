package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.util.List;

/** Structured vision result before it is persisted as a user-visible candidate draft. */
public record ClothingCandidateDraft(
        int candidateIndex,
        String displayName,
        String categoryCode,
        String colorPrimary,
        List<String> secondaryColors,
        List<String> styleTags,
        String fitCode,
        List<String> seasonTags,
        String analysisAttributesJson,
        BigDecimal analysisConfidence,
        BigDecimal qualityScore,
        ClothingCompletenessStatus completenessStatus,
        String retakeGuidance
) { }
