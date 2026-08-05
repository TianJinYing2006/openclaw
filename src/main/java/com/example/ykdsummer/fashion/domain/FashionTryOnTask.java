package com.example.ykdsummer.fashion.domain;

import java.time.Instant;

/**
 * Version-pinned background task for rendering one garment on one saved person template.
 *
 * <p>{@code wardrobeItemId} is {@code null} when the garment comes from a recommended
 * reference outfit instead of the user's own wardrobe ({@code garmentSource == "reference"}).</p>
 */
public record FashionTryOnTask(
        String id,
        long appUserId,
        String instanceId,
        String personTemplateId,
        Long wardrobeItemId,
        long personAssetVersionId,
        long garmentAssetVersionId,
        String garmentSource,
        String referenceOutfitId,
        String garmentCategoryCode,
        FashionTryOnTaskStatus status,
        int attemptCount,
        Long outputAssetVersionId,
        String failureSummary,
        Instant claimedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
