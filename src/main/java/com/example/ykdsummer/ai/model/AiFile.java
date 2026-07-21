package com.example.ykdsummer.ai.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * 一个只参与当前 Responses 请求的文件。文件字节不会写入聊天历史或本地磁盘。
 */
public record AiFile(String fileName, String mediaType, byte[] bytes) {

    public AiFile {
        Objects.requireNonNull(fileName, "fileName cannot be null");
        Objects.requireNonNull(mediaType, "mediaType cannot be null");
        Objects.requireNonNull(bytes, "bytes cannot be null");
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName cannot be blank");
        }
        if (mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType cannot be blank");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
