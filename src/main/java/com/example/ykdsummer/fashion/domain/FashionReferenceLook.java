package com.example.ykdsummer.fashion.domain;

import java.time.Instant;
import java.util.List;

/** A public outfit image and all normalized garments visible in it. */
public record FashionReferenceLook(
        long id,
        String referenceCode,
        String displayName,
        String imageAssetId,
        int imageAssetVersion,
        String imageMediaType,
        String sourceFileName,
        String sourceUrl,
        String sourceSite,
        String usageRights,
        String sha256,
        String phash,
        String annotationSchemaVersion,
        String annotationJson,
        String status,
        List<FashionReferenceGarment> garments,
        Instant createdAt,
        Instant updatedAt
) {
    public FashionReferenceLook {
        garments = garments == null ? List.of() : List.copyOf(garments);
    }
}
