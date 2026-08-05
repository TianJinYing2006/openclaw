package com.example.ykdsummer.fashion.runtime;

/** Published when a background wardrobe photo analysis ends in a durable failure. */
public record FashionWardrobePhotoAnalysisFailedEvent(
        String userId,
        String imageAssetId,
        int imageVersion,
        String failureSummary
) {
    public FashionWardrobePhotoAnalysisFailedEvent {
        userId = safe(userId);
        imageAssetId = safe(imageAssetId);
        imageVersion = Math.max(0, imageVersion);
        failureSummary = safe(failureSummary);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
