package com.example.ykdsummer.fashion.domain;

import java.time.Instant;

/** Version-pinned background task for rendering one wardrobe garment on one saved person template. */
public record FashionTryOnTask(
        String id,
        long appUserId,
        String instanceId,
        String personTemplateId,
        long wardrobeItemId,
        long personAssetVersionId,
        long garmentAssetVersionId,
        FashionTryOnTaskStatus status,
        int attemptCount,
        Long outputAssetVersionId,
        String failureSummary,
        Instant claimedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) { }
