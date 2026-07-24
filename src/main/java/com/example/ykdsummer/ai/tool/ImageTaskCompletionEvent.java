package com.example.ykdsummer.ai.tool;

/** 后台图片任务持久化成功后触发的事件，供消息渠道推送最终图片。 */
public record ImageTaskCompletionEvent(
        String userId,
        String taskId,
        byte[] imageBytes,
        String assetId,
        int assetVersion
) {
    public ImageTaskCompletionEvent {
        userId = safe(userId);
        taskId = safe(taskId);
        imageBytes = imageBytes == null ? new byte[0] : imageBytes.clone();
        assetId = safe(assetId);
        assetVersion = Math.max(0, assetVersion);
    }

    @Override
    public byte[] imageBytes() {
        return imageBytes.clone();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
