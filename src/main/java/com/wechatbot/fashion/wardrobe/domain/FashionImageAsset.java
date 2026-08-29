package com.wechatbot.fashion.wardrobe.domain;

/** A versioned image asset that was already verified to belong to the current Fashion user. */
public record FashionImageAsset(long assetVersionId, String assetId, int version, String mediaType) { }
