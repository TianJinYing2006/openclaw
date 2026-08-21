package com.example.ykdsummer.fashion.wardrobe.domain;

/** Durable state of a person-template plus wardrobe-item virtual try-on request. */
public enum FashionTryOnTaskStatus {
    SUBMITTED,
    PROCESSING,
    SUCCEEDED,
    FAILED
}
