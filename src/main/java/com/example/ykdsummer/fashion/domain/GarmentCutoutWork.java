package com.example.ykdsummer.fashion.domain;

/** Atomically claimed cutout work with all data needed by an asynchronous worker. */
public record GarmentCutoutWork(
        String externalUserId,
        GarmentCutoutTask task,
        ClothingCandidate candidate,
        FashionImageAsset sourceImage
) { }
