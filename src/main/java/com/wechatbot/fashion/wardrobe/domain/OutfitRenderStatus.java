package com.wechatbot.fashion.wardrobe.domain;

/** Durable lifecycle for one generated outfit board. */
public enum OutfitRenderStatus {
    SUBMITTED,
    PROCESSING,
    SUCCEEDED,
    FALLBACK,
    FAILED
}
