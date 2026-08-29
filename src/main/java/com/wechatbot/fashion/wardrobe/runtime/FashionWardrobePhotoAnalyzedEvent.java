package com.wechatbot.fashion.wardrobe.runtime;

import java.util.List;

/** Published after a wardrobe photo has been analyzed in the background and candidates are ready to review. */
public record FashionWardrobePhotoAnalyzedEvent(
        String userId,
        String imageAssetId,
        int imageVersion,
        String summary,
        List<String> candidateNames
) {
    public FashionWardrobePhotoAnalyzedEvent {
        userId = safe(userId);
        imageAssetId = safe(imageAssetId);
        imageVersion = Math.max(0, imageVersion);
        summary = safe(summary);
        candidateNames = candidateNames == null ? List.of() : List.copyOf(candidateNames);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
