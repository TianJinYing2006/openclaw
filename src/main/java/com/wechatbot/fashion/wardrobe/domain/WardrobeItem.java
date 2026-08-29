package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A user-owned garment. Image bytes remain in the existing versioned asset store. */
public record WardrobeItem(
        long id,
        long appUserId,
        String instanceId,
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
        String itemStatus,
        String analysisStatus,
        int analysisVersion,
        BigDecimal attributeConfidence,
        String source,
        String notes,
        String annotationSchemaVersion,
        String attributesJson,
        Instant createdAt,
        Instant updatedAt
) {
    /** Compatibility constructor for records created before the unified annotation contract. */
    public WardrobeItem(
            long id, long appUserId, String instanceId, String categoryCode, String colorPrimary,
            List<String> secondaryColors, List<String> styleTags, String fitCode, String patternCode,
            List<String> seasonTags, List<String> occasionTags, String material, String itemStatus,
            String analysisStatus, int analysisVersion, BigDecimal attributeConfidence, String source,
            String notes, Instant createdAt, Instant updatedAt
    ) {
        this(id, appUserId, instanceId, "", "", categoryCode, colorPrimary, secondaryColors, styleTags,
                fitCode, patternCode, seasonTags, occasionTags, material, itemStatus, analysisStatus,
                analysisVersion, attributeConfidence, source, notes, "1.0.0", "{}", createdAt, updatedAt);
    }
}
