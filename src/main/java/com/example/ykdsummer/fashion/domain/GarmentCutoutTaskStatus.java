package com.example.ykdsummer.fashion.domain;

/** Durable execution state for a candidate's independently retryable cutout render. */
public enum GarmentCutoutTaskStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
}
