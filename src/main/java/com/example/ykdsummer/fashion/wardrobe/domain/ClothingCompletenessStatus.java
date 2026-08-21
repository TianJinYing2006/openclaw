package com.example.ykdsummer.fashion.wardrobe.domain;

/** Whether the source image is reliable enough to create a standalone garment candidate. */
public enum ClothingCompletenessStatus {
    READY,
    RETAKE_REQUIRED,
    UNSUPPORTED
}
