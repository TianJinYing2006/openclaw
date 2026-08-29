package com.example.ykdsummer.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.util.List;

/** Administrator-maintained input for a product. Reusing productCode updates the same catalog item. */
public record FashionProductDraft(
        String productCode,
        String title,
        String brand,
        String categoryCode,
        String subCategoryCode,
        String genderTarget,
        String colorPrimary,
        List<String> secondaryColors,
        List<String> styleTags,
        List<String> seasonTags,
        List<String> occasionTags,
        String material,
        String fitCode,
        String patternCode,
        BigDecimal price,
        String currency,
        String availabilityStatus,
        int featuredRank,
        String source,
        String sourceProductId,
        String sourceUrl,
        String textDescription,
        String primaryImageUrl
) { }
