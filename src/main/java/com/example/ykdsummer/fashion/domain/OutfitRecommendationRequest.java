package com.example.ykdsummer.fashion.domain;

import java.util.List;

/** User-scoped input for one deterministic wardrobe recommendation run. */
public record OutfitRecommendationRequest(
        String externalUserId,
        long anchorWardrobeItemId,
        List<String> occasionTags,
        List<String> seasonTags,
        String weatherSummary,
        List<String> styleTags,
        String targetTime,
        int maxResults
) {
    public OutfitRecommendationRequest {
        externalUserId = safe(externalUserId);
        occasionTags = copy(occasionTags);
        seasonTags = copy(seasonTags);
        weatherSummary = safe(weatherSummary);
        styleTags = copy(styleTags);
        targetTime = safe(targetTime);
        maxResults = Math.max(1, Math.min(maxResults, 3));
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream().map(OutfitRecommendationRequest::safe)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
