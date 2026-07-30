package com.example.ykdsummer.fashion.domain;

import java.math.BigDecimal;
import java.util.List;

/** One normalized garment inside a platform-owned public outfit reference. */
public record FashionReferenceGarment(
        long id,
        long referenceLookId,
        int itemIndex,
        String displayName,
        String categoryCode,
        String subCategoryCode,
        String targetGender,
        String colorPrimary,
        List<String> secondaryColors,
        List<String> accentColors,
        List<String> styleTags,
        String fitCode,
        String patternCode,
        String silhouetteCode,
        String lengthCode,
        List<String> materialTags,
        List<String> seasonTags,
        List<String> occasionTags,
        int formalityLevel,
        String visibilityStatus,
        BigDecimal visibleRatio,
        BigDecimal confidence,
        String attributesJson,
        String cutoutAssetId,
        int cutoutAssetVersion,
        String cutoutAssetMediaType,
        String cutoutStatus,
        String cutoutSourcePath,
        String cutoutSha256,
        String cutoutModel,
        BigDecimal cutoutDurationSeconds,
        String cutoutError
) {
    /** Compatibility constructor for records that do not have a cutout asset yet. */
    public FashionReferenceGarment(long id, long referenceLookId, int itemIndex, String displayName,
            String categoryCode, String subCategoryCode, String targetGender, String colorPrimary,
            List<String> secondaryColors, List<String> accentColors, List<String> styleTags, String fitCode,
            String patternCode, String silhouetteCode, String lengthCode, List<String> materialTags,
            List<String> seasonTags, List<String> occasionTags, int formalityLevel, String visibilityStatus,
            BigDecimal visibleRatio, BigDecimal confidence, String attributesJson) {
        this(id, referenceLookId, itemIndex, displayName, categoryCode, subCategoryCode, targetGender, colorPrimary,
                secondaryColors, accentColors, styleTags, fitCode, patternCode, silhouetteCode, lengthCode,
                materialTags, seasonTags, occasionTags, formalityLevel, visibilityStatus, visibleRatio, confidence,
                attributesJson, "", 0, "image/png", "PENDING", "", "", "", BigDecimal.ZERO, "");
    }
}
