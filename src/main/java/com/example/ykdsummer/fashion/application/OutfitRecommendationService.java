package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.config.OutfitRecommendationProperties;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.FashionUserPreference;
import com.example.ykdsummer.fashion.domain.OutfitRecommendationRequest;
import com.example.ykdsummer.fashion.domain.OutfitRecommendationResult;
import com.example.ykdsummer.fashion.domain.OutfitRenderStatus;
import com.example.ykdsummer.fashion.domain.SemanticReferenceGarmentMatch;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import com.example.ykdsummer.fashion.persistence.OutfitRecommendationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Orchestrates user-scoped wardrobe facts, public evidence retrieval, deterministic ranking, and snapshot persistence. */
@Service
@ConditionalOnBean({FashionReferenceSemanticSearchService.class, OutfitRecommendationRepository.class})
@ConditionalOnProperty(prefix = "app.fashion.outfit-recommendation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OutfitRecommendationService {
    private final FashionCoreService wardrobe;
    private final FashionReferenceSemanticSearchService publicSearch;
    private final OutfitRecommendationEngine engine;
    private final OutfitRecommendationRepository recommendations;
    private final OutfitRecommendationProperties properties;

    public OutfitRecommendationService(
            FashionCoreService wardrobe,
            FashionReferenceSemanticSearchService publicSearch,
            OutfitRecommendationEngine engine,
            OutfitRecommendationRepository recommendations,
            OutfitRecommendationProperties properties
    ) {
        this.wardrobe = wardrobe;
        this.publicSearch = publicSearch;
        this.engine = engine;
        this.recommendations = recommendations;
        this.properties = properties;
    }

    public OutfitRecommendationResult recommend(OutfitRecommendationRequest request) {
        if (request == null || request.externalUserId().isBlank()) {
            throw new IllegalArgumentException("Current user is required");
        }
        WardrobeItem anchor = wardrobe.ownedWardrobeItem(request.externalUserId(), request.anchorWardrobeItemId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The selected wardrobe item is not active or does not belong to the current user"));
        List<WardrobeItem> active = wardrobe.activeWardrobeItems(request.externalUserId(), 1000);
        Map<Long, FashionImageAsset> images = wardrobe.primaryWardrobeImages(request.externalUserId(),
                active.stream().map(WardrobeItem::id).toList());
        FashionImageAsset anchorImage = images.get(anchor.id());
        if (anchorImage == null) {
            throw new IllegalArgumentException("The selected wardrobe item has no usable primary image");
        }
        List<WardrobeItem> renderable = active.stream().filter(item -> images.containsKey(item.id())).toList();
        WardrobeSearchCriteria sameRole = WardrobeSearchCriteria.from(
                OutfitRecommendationEngine.role(anchor), null, List.of(), null, null,
                List.of(), List.of(), null);
        List<SemanticReferenceGarmentMatch> publicMatches = publicSearch.searchGarments(
                semanticQuery(anchor, request), sameRole, properties.getPublicCandidateLimit());
        List<FashionUserPreference> preferences = wardrobe.preferences(request.externalUserId());
        Set<Long> recent = recommendations.recentlyRecommendedItemIds(request.externalUserId(), 60);
        OutfitRecommendationEngine.EngineResult decision = engine.recommend(
                anchor, renderable, publicMatches, preferences, recent, request);

        String recommendationId = UUID.randomUUID().toString();
        List<OutfitRecommendationResult.Option> options = new ArrayList<>();
        int rank = 1;
        for (OutfitRecommendationEngine.Candidate candidate : decision.candidates()) {
            List<OutfitRecommendationResult.Item> items = candidate.items().entrySet().stream()
                    .sorted(java.util.Comparator.comparingInt(
                            (Map.Entry<String, WardrobeItem> entry) -> roleOrder(entry.getKey())))
                    .map(entry -> resultItem(entry.getKey(), entry.getValue(), images.get(entry.getValue().id())))
                    .toList();
            options.add(new OutfitRecommendationResult.Option(
                    UUID.randomUUID().toString(), rank++, items, candidate.totalScore(), candidate.breakdown(),
                    candidate.evidence(), summary(items), OutfitRenderStatus.SUBMITTED, "", 0, ""));
        }
        OutfitRecommendationResult result = new OutfitRecommendationResult(recommendationId, anchor.id(),
                options, decision.missingItem(), Instant.now());
        return recommendations.save(request, result);
    }

    public Optional<OutfitRecommendationResult> latest(String externalUserId) {
        return recommendations.latest(externalUserId);
    }

    private static OutfitRecommendationResult.Item resultItem(
            String role, WardrobeItem item, FashionImageAsset image
    ) {
        return new OutfitRecommendationResult.Item(item.id(), role, displayName(item), item.categoryCode(),
                item.colorPrimary(), image.assetVersionId(), image.assetId(), image.version());
    }

    private static String semanticQuery(WardrobeItem anchor, OutfitRecommendationRequest request) {
        List<String> parts = new ArrayList<>();
        add(parts, displayName(anchor));
        add(parts, anchor.parentCategoryCode());
        add(parts, anchor.categoryCode());
        add(parts, anchor.colorPrimary());
        parts.addAll(anchor.styleTags());
        add(parts, anchor.fitCode());
        add(parts, anchor.patternCode());
        parts.addAll(anchor.seasonTags());
        parts.addAll(anchor.occasionTags());
        add(parts, anchor.material());
        parts.addAll(request.styleTags());
        parts.addAll(request.occasionTags());
        parts.addAll(request.seasonTags());
        return String.join(" ", parts);
    }

    private static String summary(List<OutfitRecommendationResult.Item> items) {
        return items.stream().map(OutfitRecommendationService::itemSummary)
                .collect(java.util.stream.Collectors.joining(" + "));
    }

    private static String itemSummary(OutfitRecommendationResult.Item item) {
        String name = safe(item.displayName());
        if (!name.isBlank()) return name;
        return (label(item.colorPrimary()) + label(item.categoryCode())).strip();
    }

    private static String displayName(WardrobeItem item) {
        String value = safe(item.displayName());
        return value.isBlank() ? FashionItemNamer.nameFor(item.categoryCode(), item.colorPrimary(),
                item.attributesJson(), item.fitCode(), item.patternCode()) : value;
    }

    private static String label(String value) {
        return switch (safe(value).toUpperCase(java.util.Locale.ROOT)) {
            case "BLACK" -> "黑色";
            case "WHITE" -> "白色";
            case "GRAY" -> "灰色";
            case "LIGHT_GRAY" -> "浅灰色";
            case "DARK_GRAY" -> "深灰色";
            case "NAVY" -> "藏青色";
            case "DENIM_BLUE" -> "牛仔蓝";
            case "T_SHIRT" -> "T恤";
            case "SHIRT" -> "衬衫";
            case "JACKET" -> "外套";
            case "JEANS" -> "牛仔裤";
            case "STRAIGHT_PANTS" -> "直筒裤";
            default -> safe(value);
        };
    }

    private static void add(List<String> values, String value) {
        String cleaned = safe(value);
        if (!cleaned.isBlank()) values.add(cleaned);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private static int roleOrder(String role) {
        return switch (safe(role).toUpperCase(java.util.Locale.ROOT)) {
            case "TOP" -> 1;
            case "BOTTOM" -> 2;
            case "OUTERWEAR" -> 3;
            default -> 9;
        };
    }
}
