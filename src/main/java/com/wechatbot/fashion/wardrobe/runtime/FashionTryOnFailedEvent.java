package com.wechatbot.fashion.wardrobe.runtime;

/** Published when a virtual try-on task fails, so the user gets a notification instead of silence. */
public record FashionTryOnFailedEvent(
        String userId,
        String taskId,
        String reason
) {
    public FashionTryOnFailedEvent {
        userId = safe(userId);
        taskId = safe(taskId);
        reason = safe(reason);
    }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
