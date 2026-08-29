package com.wechatbot.fashion.wardrobe.domain;

import java.util.List;

/** User corrections allowed while a clothing candidate remains a draft. */
public record ClothingCandidateLabels(
        String displayName,
        String categoryCode,
        String colorPrimary,
        List<String> secondaryColors,
        List<String> styleTags,
        String fitCode,
        List<String> seasonTags,
        String material,
        String patternCode,
        List<String> occasionTags
) { }
