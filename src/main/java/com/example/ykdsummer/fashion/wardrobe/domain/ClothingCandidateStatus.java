package com.example.ykdsummer.fashion.wardrobe.domain;

/** Lifecycle before a candidate becomes a confirmed wardrobe item. */
public enum ClothingCandidateStatus {
    PENDING_SELECTION,
    CUTOUT_SUBMITTED,
    AWAITING_FINAL_CONFIRMATION,
    FINAL_CONFIRMED,
    REJECTED,
    RETAKE_REQUIRED,
    EXPIRED,
    FAILED
}
