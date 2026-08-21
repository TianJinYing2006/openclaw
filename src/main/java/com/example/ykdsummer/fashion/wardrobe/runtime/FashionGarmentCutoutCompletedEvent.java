package com.example.ykdsummer.fashion.wardrobe.runtime;

/** Published only after a cutout image has been persisted and is ready for the user's final wardrobe confirmation. */
public record FashionGarmentCutoutCompletedEvent(
        String userId,
        String taskId,
        String candidateId,
        byte[] imageBytes,
        String assetId,
        int assetVersion
) {
    public FashionGarmentCutoutCompletedEvent {
        userId = safe(userId);
        taskId = safe(taskId);
        candidateId = safe(candidateId);
        imageBytes = imageBytes == null ? new byte[0] : imageBytes.clone();
        assetId = safe(assetId);
        assetVersion = Math.max(0, assetVersion);
    }

    @Override
    public byte[] imageBytes() { return imageBytes.clone(); }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
