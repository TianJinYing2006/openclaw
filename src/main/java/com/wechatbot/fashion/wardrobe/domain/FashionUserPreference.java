package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Current normalized preference used by future ranking; raw feedback is added in a later phase. */
public record FashionUserPreference(
        long appUserId,
        String dimensionCode,
        String valueCode,
        String polarity,
        BigDecimal weight,
        BigDecimal confidence,
        String source,
        String lastEvidence,
        Instant createdAt,
        Instant updatedAt
) { }
