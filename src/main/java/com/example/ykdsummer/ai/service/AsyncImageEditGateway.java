package com.example.ykdsummer.ai.service;

import java.util.List;
import java.time.Duration;

/** 独立于 OpenAI Images API 的异步参考图编辑协议。 */
public interface AsyncImageEditGateway {
    EditResult edit(String prompt, String referenceImageUrl);

    /**
     * 多参考图编辑入口。单图实现可以保持原有行为；不支持多图的供应商必须明确返回不支持，
     * 而不是悄悄丢弃人物图或成衣图。
     */
    default EditResult edit(String prompt, List<String> referenceImageUrls) {
        if (referenceImageUrls == null || referenceImageUrls.isEmpty()) {
            return EditResult.error("原图读取地址无效，无法修改图片");
        }
        if (referenceImageUrls.size() == 1) {
            return edit(prompt, referenceImageUrls.getFirst());
        }
        return EditResult.error("当前图片编辑服务不支持多参考图编辑");
    }

    /** Allows a long-running background workflow to opt into its own bounded provider deadline. */
    default EditResult edit(String prompt, List<String> referenceImageUrls, Duration timeout) {
        return edit(prompt, referenceImageUrls);
    }

    record EditResult(byte[] imageBytes, String remoteUrl, String errorMessage) {
        public static EditResult success(byte[] bytes, String remoteUrl) { return new EditResult(bytes, remoteUrl, null); }
        public static EditResult error(String message) { return new EditResult(null, null, message); }
        public boolean hasImage() { return imageBytes != null && imageBytes.length > 0; }
    }
}
