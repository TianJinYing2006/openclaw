package com.wechatbot.fashion.ai.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * 一张只存在于当前模型请求内存中的图片。mediaType 告诉 Responses API 是 JPEG、PNG
 * 还是 WebP；bytes 是从腾讯 CDN 下载并经过 SDK AES 解密后的原始内容。
 */
public record AiImage(String mediaType, byte[] bytes, Detail detail) {

    /** 普通图片保持 AUTO；视频帧使用 LOW，控制多图请求的体积、耗时和输入 token。 */
    public enum Detail {
        AUTO,
        LOW
    }

    public AiImage(String mediaType, byte[] bytes) {
        this(mediaType, bytes, Detail.AUTO);
    }

    public AiImage {
        Objects.requireNonNull(mediaType, "mediaType cannot be null");
        Objects.requireNonNull(bytes, "bytes cannot be null");
        Objects.requireNonNull(detail, "detail cannot be null");
        // byte[] 可被修改，存入 record 前复制一次，避免调用方之后改变这张图片。
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        // 读取时也返回副本，不把内部数组直接暴露出去。
        return Arrays.copyOf(bytes, bytes.length);
    }
}
