package com.wechatbot.fashion.wardrobe.runtime;

/** Published only after a virtual try-on image is durable in the asset store and task record. */
public record FashionTryOnCompletedEvent(
        String userId,
        String taskId,
        long wardrobeItemId,
        byte[] imageBytes,
        String assetId,
        int assetVersion
) {
    public FashionTryOnCompletedEvent {
        userId = safe(userId);
        taskId = safe(taskId);
        imageBytes = imageBytes == null ? new byte[0] : imageBytes.clone();
        assetId = safe(assetId);
        assetVersion = Math.max(0, assetVersion);
    }
    @Override public byte[] imageBytes() { return imageBytes.clone(); }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
