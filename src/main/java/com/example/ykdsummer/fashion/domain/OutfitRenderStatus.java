package com.example.ykdsummer.fashion.domain;

/** Durable lifecycle for one generated outfit board. */
public enum OutfitRenderStatus {
    SUBMITTED,
    PROCESSING,
    SUCCEEDED,
    FALLBACK,
    FAILED
}
