package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.config.FashionSemanticProperties;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.domain.SemanticReferenceMatch;
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
                    .filterExpression("scope == 'PUBLIC_REFERENCE'").build());
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

    private List<SemanticReferenceMatch> fallback(WardrobeSearchCriteria criteria, int limit) {
        return repository.activeLooks(criteria.hasFilters() ? 2000 : limit).stream().filter(criteria::matches)
                .limit(limit).map(look -> new SemanticReferenceMatch(look, -1d)).toList();
    }
    private static Long number(Object value) {
        try { return value == null ? null : Long.parseLong(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
}
