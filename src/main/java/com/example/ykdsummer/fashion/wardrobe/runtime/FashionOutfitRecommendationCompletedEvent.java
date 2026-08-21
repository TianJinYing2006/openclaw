package com.example.ykdsummer.fashion.wardrobe.runtime;

import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRenderStatus;

/** Published only after an outfit board and its option state are durable. */
public record FashionOutfitRecommendationCompletedEvent(
        String userId,
        String recommendationId,
        String optionId,
        int rank,
        String displaySummary,
        OutfitRenderStatus renderStatus,
        byte[] imageBytes,
        String assetId,
        int assetVersion
) {
    public FashionOutfitRecommendationCompletedEvent {
        userId = safe(userId);
        recommendationId = safe(recommendationId);
        optionId = safe(optionId);
        rank = Math.max(1, Math.min(rank, 3));
        displaySummary = safe(displaySummary);
        renderStatus = renderStatus == null ? OutfitRenderStatus.SUCCEEDED : renderStatus;
        imageBytes = imageBytes == null ? new byte[0] : imageBytes.clone();
        assetId = safe(assetId);
        assetVersion = Math.max(0, assetVersion);
    }
    @Override public byte[] imageBytes() { return imageBytes.clone(); }
    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
