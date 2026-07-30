package com.example.ykdsummer.fashion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A user-visible analysis draft. It is not a wardrobe item until final confirmation. */
public record ClothingCandidate(
        String id,
        long appUserId,
        String instanceId,
        long sourceAssetVersionId,
        int candidateIndex,
        String displayName,
        String categoryCode,
        String colorPrimary,
        List<String> secondaryColors,
        List<String> styleTags,
        String fitCode,
        List<String> seasonTags,
        String analysisAttributesJson,
        BigDecimal analysisConfidence,
        BigDecimal qualityScore,
        ClothingCompletenessStatus completenessStatus,
        String retakeGuidance,
        ClothingCandidateStatus status,
        Long currentCutoutAssetVersionId,
        Long confirmedWardrobeItemId,
        String provider,
        String model,
        String promptVersion,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) { }
