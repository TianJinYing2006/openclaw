package com.example.ykdsummer.fashion.wardrobe.domain;

import java.time.Instant;

/** A single rendering attempt for one selected clothing candidate. */
public record GarmentCutoutTask(
        String id,
        String candidateId,
        long appUserId,
        String instanceId,
        long sourceAssetVersionId,
        int attemptNumber,
        String instructionText,
        GarmentCutoutTaskStatus status,
        Long outputAssetVersionId,
        String failureSummary,
        Instant claimedAt,
        Instant completedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) { }
