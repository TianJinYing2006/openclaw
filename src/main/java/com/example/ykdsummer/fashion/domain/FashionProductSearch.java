package com.example.ykdsummer.fashion.domain;

import java.math.BigDecimal;

/** Structured catalog filters used by the Agent. All fields are optional except the bounded result limit. */
public record FashionProductSearch(
        String keyword,
        String categoryCode,
        String color,
        String styleTag,
        String seasonTag,
        String occasionTag,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Integer limit
) { }
