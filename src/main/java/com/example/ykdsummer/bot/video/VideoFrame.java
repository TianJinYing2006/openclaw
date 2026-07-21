package com.example.ykdsummer.bot.video;

import com.example.ykdsummer.ai.model.AiImage;

import java.util.Objects;

/** 一张从视频中抽出的模型图片，以及它在原视频中的时间位置。 */
public record VideoFrame(long timestampMs, AiImage image) {

    public VideoFrame {
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs cannot be negative");
        }
        Objects.requireNonNull(image, "image cannot be null");
    }
}
