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
        String attributesJson
) { }
