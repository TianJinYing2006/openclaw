package com.example.ykdsummer.fashion.wardrobe.runtime;

/** Published after a garment cutout task reaches a durable failed state. */
public record FashionGarmentCutoutFailedEvent(
        String userId,
        String taskId,
        String candidateId,
        String failureSummary
) {
    public FashionGarmentCutoutFailedEvent {
        userId = safe(userId);
        taskId = safe(taskId);
        candidateId = safe(candidateId);
        failureSummary = safe(failureSummary);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
