package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** A user-approved private source photo for later virtual try-on rendering. */
public record FashionPersonTemplate(
        String id,
        long appUserId,
        String instanceId,
        long sourceAssetVersionId,
        String sourceAssetId,
        int sourceAssetVersion,
        String displayName,
        FashionPersonTemplateStatus status,
        String suitabilitySummary,
        String retakeGuidance,
        BigDecimal analysisConfidence,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) { }
