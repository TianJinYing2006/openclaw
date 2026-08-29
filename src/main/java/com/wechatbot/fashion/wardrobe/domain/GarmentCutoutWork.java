package com.wechatbot.fashion.wardrobe.domain;

/** Atomically claimed cutout work with all data needed by an asynchronous worker. */
public record GarmentCutoutWork(
        String externalUserId,
        GarmentCutoutTask task,
        ClothingCandidate candidate,
        FashionImageAsset sourceImage
) { }
