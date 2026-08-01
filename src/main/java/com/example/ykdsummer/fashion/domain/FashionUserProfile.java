package com.example.ykdsummer.fashion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Persisted long-term Fashion profile. All fields are optional until the user provides them. */
public record FashionUserProfile(
        long appUserId,
        String genderExpression,
        String styleSummary,
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        List<String> commonOccasions,
        int profileCompleteness,
        Instant privacyConsentAt,
        Instant createdAt,
        Instant updatedAt
) { }
