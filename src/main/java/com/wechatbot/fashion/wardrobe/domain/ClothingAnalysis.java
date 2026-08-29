package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Structured analysis attached to an immutable source asset version. */
public record ClothingAnalysis(
        long id,
        long appUserId,
        String instanceId,
        long assetVersionId,
        Long wardrobeItemId,
        String categoryCode,
        String attributesJson,
        BigDecimal confidence,
        String analysisStatus,
        String provider,
        String model,
        String promptVersion,
        int analysisVersion,
        String failureSummary,
        Instant createdAt,
        Instant updatedAt
) { }
