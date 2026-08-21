package com.example.ykdsummer.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.util.List;

/** User-provided profile facts. Inferred preferences belong in FashionUserPreference instead. */
public record FashionProfileUpdate(
        String genderExpression,
        String styleSummary,
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        List<String> commonOccasions,
        int profileCompleteness,
        boolean grantPrivacyConsent
) { }
