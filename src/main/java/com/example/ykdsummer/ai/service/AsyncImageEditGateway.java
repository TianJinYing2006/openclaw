package com.example.ykdsummer.ai.service;

/** 独立于 OpenAI Images API 的异步参考图编辑协议。 */
public interface AsyncImageEditGateway {
    EditResult edit(String prompt, String referenceImageUrl);

    record EditResult(byte[] imageBytes, String remoteUrl, String errorMessage) {
        public static EditResult success(byte[] bytes, String remoteUrl) { return new EditResult(bytes, remoteUrl, null); }
        public static EditResult error(String message) { return new EditResult(null, null, message); }
        public boolean hasImage() { return imageBytes != null && imageBytes.length > 0; }
    }
}
