package com.wechatbot.fashion.wardrobe.domain;

import java.time.Instant;

/** One successful preview in a candidate's selectable garment-draft history. */
public record GarmentDraftVersion(
        int versionNumber,
        String taskId,
        String instruction,
        FashionImageAsset image,
        Instant createdAt,
        boolean current
) { }
