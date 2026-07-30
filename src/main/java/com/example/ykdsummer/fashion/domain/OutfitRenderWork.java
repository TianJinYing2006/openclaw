package com.example.ykdsummer.fashion.domain;

import java.util.List;

/** Atomically claimed outfit option plus immutable source image versions. */
public record OutfitRenderWork(
        String externalUserId,
        String recommendationId,
        String optionId,
        int rank,
        String displaySummary,
        List<SourceItem> items
) {
    public OutfitRenderWork {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record SourceItem(
            long wardrobeItemId,
            String role,
            String displayName,
            FashionImageAsset image
    ) { }
}
