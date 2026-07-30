package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.config.FashionSemanticProperties;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.domain.SemanticReferenceMatch;
import com.example.ykdsummer.fashion.domain.SemanticReferenceGarmentMatch;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import com.example.ykdsummer.fashion.persistence.FashionReferenceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = {"app.fashion.semantic.enabled", "app.fashion.reference.enabled"}, havingValue = "true")
public class FashionReferenceSemanticSearchService {
    private static final Logger log = LoggerFactory.getLogger(FashionReferenceSemanticSearchService.class);
    private final FashionReferenceRepository repository;
    private final VectorStore vectorStore;
    private final FashionSemanticProperties properties;

    public FashionReferenceSemanticSearchService(FashionReferenceRepository repository,
            @Qualifier("fashionVectorStore") VectorStore vectorStore, FashionSemanticProperties properties) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public List<SemanticReferenceMatch> search(String query, WardrobeSearchCriteria criteria, int limit) {
        int bounded = Math.max(1, Math.min(limit, 20));
        WardrobeSearchCriteria effective = criteria == null
                ? WardrobeSearchCriteria.from(null, null, List.of(), null, null, List.of(), List.of(), null) : criteria;
        String cleaned = query == null ? "" : query.replace('\0', ' ').strip();
        if (cleaned.isBlank()) return fallback(effective, bounded);
        try {
            int topK = Math.min(100, Math.max(bounded, bounded * properties.getCandidateMultiplier()));
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder().query(cleaned).topK(topK)
                    .similarityThreshold(properties.getSimilarityThreshold())
                    .filterExpression("scope == 'PUBLIC_REFERENCE' && entityType == 'REFERENCE_LOOK'").build());
            Map<Long, Double> scores = new LinkedHashMap<>();
            hits.forEach(hit -> {
                Long id = number(hit.getMetadata().get("referenceLookId"));
                if (id != null) scores.putIfAbsent(id, hit.getScore() == null ? 0d : hit.getScore());
            });
            return scores.entrySet().stream().map(entry -> repository.findById(entry.getKey())
                            .filter(look -> "ACTIVE".equals(look.status()) && effective.matches(look))
                            .map(look -> new SemanticReferenceMatch(look, entry.getValue())).orElse(null))
                    .filter(java.util.Objects::nonNull).limit(bounded).toList();
        } catch (RuntimeException failure) {
            log.warn("Public fashion semantic search unavailable, using MySQL filters: {}", failure.toString());
            return fallback(effective, bounded);
        }
    }

    public List<SemanticReferenceGarmentMatch> searchGarments(
            String query, WardrobeSearchCriteria criteria, int limit
    ) {
        int bounded = Math.max(1, Math.min(limit, 50));
        WardrobeSearchCriteria effective = criteria == null
                ? WardrobeSearchCriteria.from(null, null, List.of(), null, null, List.of(), List.of(), null) : criteria;
        String cleaned = query == null ? "" : query.replace('\0', ' ').strip();
        if (cleaned.isBlank()) return garmentFallback(effective, bounded);
        try {
            int topK = Math.min(200, Math.max(bounded, bounded * properties.getCandidateMultiplier()));
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder().query(cleaned).topK(topK)
                    .similarityThreshold(properties.getSimilarityThreshold())
                    .filterExpression(garmentFilter(effective)).build());
            Map<GarmentKey, Double> scores = new LinkedHashMap<>();
            hits.forEach(hit -> {
                Long lookId = number(hit.getMetadata().get("referenceLookId"));
                Long itemIndex = number(hit.getMetadata().get("itemIndex"));
                if (lookId != null && itemIndex != null) {
                    scores.putIfAbsent(new GarmentKey(lookId, itemIndex.intValue()),
                            hit.getScore() == null ? 0d : hit.getScore());
                }
            });
            Map<Long, FashionReferenceLook> looks = repository.findActiveByIds(
                    scores.keySet().stream().map(GarmentKey::lookId).distinct().toList()).stream()
                    .collect(java.util.stream.Collectors.toMap(FashionReferenceLook::id, value -> value));
            return scores.entrySet().stream().map(entry -> {
                        FashionReferenceLook look = looks.get(entry.getKey().lookId());
                        if (look == null) return null;
                        return look.garments().stream()
                                .filter(garment -> garment.itemIndex() == entry.getKey().itemIndex())
                                .filter(effective::matches).findFirst()
                                .map(garment -> new SemanticReferenceGarmentMatch(look, garment, entry.getValue()))
                                .orElse(null);
                    })
                    .filter(java.util.Objects::nonNull).limit(bounded).toList();
        } catch (RuntimeException failure) {
            log.warn("Public garment semantic search unavailable, using MySQL filters: {}", failure.toString());
            return garmentFallback(effective, bounded);
        }
    }

    private List<SemanticReferenceMatch> fallback(WardrobeSearchCriteria criteria, int limit) {
        return repository.activeLooks(criteria.hasFilters() ? 2000 : limit).stream().filter(criteria::matches)
                .limit(limit).map(look -> new SemanticReferenceMatch(look, -1d)).toList();
    }
    private List<SemanticReferenceGarmentMatch> garmentFallback(WardrobeSearchCriteria criteria, int limit) {
        return repository.activeLooks(2000).stream()
                .flatMap(look -> look.garments().stream()
                        .filter(criteria::matches)
                        .map(garment -> new SemanticReferenceGarmentMatch(look, garment, -1d)))
                .limit(limit).toList();
    }
    private static String garmentFilter(WardrobeSearchCriteria criteria) {
        String base = "scope == 'PUBLIC_REFERENCE' && entityType == 'REFERENCE_GARMENT'";
        if (criteria == null || criteria.categoryCodes().isEmpty()) return base;
        if (criteria.categoryCodes().contains("TOP")) return base + " && categoryCode == 'TOP'";
        if (criteria.categoryCodes().contains("BOTTOM")) return base + " && categoryCode == 'BOTTOM'";
        if (criteria.categoryCodes().contains("OUTERWEAR")) return base + " && categoryCode == 'OUTERWEAR'";
        return base;
    }
    private static Long number(Object value) {
        try { return value == null ? null : Long.parseLong(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
    private record GarmentKey(long lookId, int itemIndex) { }
}
