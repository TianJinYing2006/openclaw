package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A platform catalog product. It is deliberately not owned by one chat user. */
public record FashionProduct(
        long id,
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
        String primaryImageUrl,
        Instant createdAt,
        Instant updatedAt
) { }
