package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.util.List;

public record WardrobeItemDraft(
        String displayName,
        String parentCategoryCode,
        String categoryCode,
        String colorPrimary,
        List<String> secondaryColors,
        List<String> styleTags,
        String fitCode,
        String patternCode,
        List<String> seasonTags,
        List<String> occasionTags,
        String material,
        String source,
        String notes,
        String annotationSchemaVersion,
        String attributesJson,
        BigDecimal attributeConfidence
) {
    /** Compatibility constructor for existing manually-labelled and test records. */
    public WardrobeItemDraft(
            String categoryCode, String colorPrimary, List<String> secondaryColors, List<String> styleTags,
            String fitCode, String patternCode, List<String> seasonTags, List<String> occasionTags,
            String material, String source, String notes, BigDecimal attributeConfidence
    ) {
        this("", "", categoryCode, colorPrimary, secondaryColors, styleTags, fitCode, patternCode,
                seasonTags, occasionTags, material, source, notes, "1.0.0", "{}", attributeConfidence);
    }
}
