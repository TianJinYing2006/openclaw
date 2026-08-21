package com.example.ykdsummer.fashion.wardrobe.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Auditable recommendation result. LLMs may explain it but must not change its selected items or scores. */
public record OutfitRecommendationResult(
        String recommendationId,
        long anchorWardrobeItemId,
        List<Option> options,
        MissingItem missingItem,
        Instant createdAt
) {
    public OutfitRecommendationResult {
        recommendationId = safe(recommendationId);
        options = options == null ? List.of() : List.copyOf(options);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public record Option(
            String optionId,
            int rank,
            List<Item> items,
            double totalScore,
            Map<String, Double> scoreBreakdown,
            List<Evidence> evidence,
            String displaySummary,
            OutfitRenderStatus renderStatus,
            String outputAssetId,
            int outputAssetVersion,
            String failureSummary
    ) {
        public Option {
            optionId = safe(optionId);
            rank = Math.max(1, Math.min(rank, 3));
            items = items == null ? List.of() : List.copyOf(items);
            totalScore = bounded(totalScore);
            scoreBreakdown = scoreBreakdown == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(scoreBreakdown));
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            displaySummary = safe(displaySummary);
            renderStatus = renderStatus == null ? OutfitRenderStatus.SUBMITTED : renderStatus;
            outputAssetId = safe(outputAssetId);
            outputAssetVersion = Math.max(0, outputAssetVersion);
            failureSummary = safe(failureSummary);
        }
    }

    public record Item(
            long wardrobeItemId,
            String role,
            String displayName,
            String categoryCode,
            String colorPrimary,
            long sourceAssetVersionId,
            String sourceAssetId,
            int sourceAssetVersion
    ) {
        public Item {
            role = safe(role).toUpperCase(java.util.Locale.ROOT);
            displayName = safe(displayName);
            categoryCode = safe(categoryCode);
            colorPrimary = safe(colorPrimary);
            sourceAssetId = safe(sourceAssetId);
            sourceAssetVersion = Math.max(0, sourceAssetVersion);
        }
    }

    public record Evidence(
            long referenceLookId,
            String referenceCode,
            String companionCategory,
            String companionSummary,
            double support
    ) {
        public Evidence {
            referenceCode = safe(referenceCode);
            companionCategory = safe(companionCategory);
            companionSummary = safe(companionSummary);
            support = unit(support);
        }
    }

    public record MissingItem(
            String categoryCode,
            String summary,
            int supportingLooks,
            double confidence
    ) {
        public MissingItem {
            categoryCode = safe(categoryCode);
            summary = safe(summary);
            supportingLooks = Math.max(0, supportingLooks);
            confidence = unit(confidence);
        }
    }

    private static double bounded(double value) {
        if (!Double.isFinite(value)) return 0d;
        return Math.max(0d, Math.min(value, 100d));
    }

    private static double unit(double value) {
        if (!Double.isFinite(value)) return 0d;
        return Math.max(0d, Math.min(value, 1d));
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
