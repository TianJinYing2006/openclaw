package com.example.ykdsummer.fashion.wardrobe.domain;

/** Atomically claimed task plus the immutable source image versions required by the renderer. */
public record FashionTryOnWork(
        String externalUserId,
        FashionTryOnTask task,
        FashionImageAsset personImage,
        FashionImageAsset garmentImage,
        String garmentCategoryCode
) { }
