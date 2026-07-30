package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.config.FashionSemanticProperties;
import com.example.ykdsummer.fashion.domain.SemanticWardrobeMatch;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import com.example.ykdsummer.fashion.identity.FashionUserScope;
import com.example.ykdsummer.fashion.persistence.FashionCoreRepository;
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
@ConditionalOnProperty(prefix = "app.fashion.semantic", name = "enabled", havingValue = "true")
public class FashionSemanticSearchService {
    private static final Logger log = LoggerFactory.getLogger(FashionSemanticSearchService.class);

    private final FashionCoreRepository wardrobe;
    private final VectorStore vectorStore;
    private final FashionSemanticProperties properties;

    public FashionSemanticSearchService(
            FashionCoreRepository wardrobe,
            @Qualifier("fashionVectorStore") VectorStore vectorStore,
            FashionSemanticProperties properties
    ) {
        this.wardrobe = wardrobe;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public List<SemanticWardrobeMatch> search(
            String externalUserId, String query, WardrobeSearchCriteria criteria, int limit
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 20));
        WardrobeSearchCriteria effective = criteria == null
                ? WardrobeSearchCriteria.from(null, null, List.of(), null, null, List.of(), List.of(), null)
                : criteria;
        String cleanedQuery = query == null ? "" : query.replace('\u0000', ' ').strip();
        if (cleanedQuery.isBlank()) return structuredFallback(externalUserId, effective, boundedLimit);

        FashionUserScope scope = wardrobe.resolveUser(externalUserId);
        int topK = Math.min(100, Math.max(boundedLimit, boundedLimit * properties.getCandidateMultiplier()));
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(cleanedQuery)
                    .topK(topK)
                    .similarityThreshold(properties.getSimilarityThreshold())
                    // Spring AI's Qdrant adapter serializes metadata values as payload strings.
                    .filterExpression("scope == 'USER_WARDROBE' && appUserId == '" + scope.appUserId() + "'")
                    .build();
            List<Document> hits = vectorStore.similaritySearch(request);
            Map<Long, Double> scores = new LinkedHashMap<>();
            for (Document hit : hits) {
                Long itemId = itemId(hit.getMetadata().get("wardrobeItemId"));
                if (itemId != null) scores.putIfAbsent(itemId, hit.getScore() == null ? 0d : hit.getScore());
            }
            Map<Long, WardrobeItem> activeItems = new LinkedHashMap<>();
            wardrobe.activeWardrobeItems(externalUserId, 1000)
                    .forEach(item -> activeItems.put(item.id(), item));
            return scores.entrySet().stream()
                    .map(entry -> new SemanticWardrobeMatch(activeItems.get(entry.getKey()), entry.getValue()))
                    .filter(match -> match.item() != null && effective.matches(match.item()))
                    .limit(boundedLimit)
                    .toList();
        } catch (RuntimeException failure) {
            log.warn("Fashion semantic search unavailable, falling back to MySQL filters: {}", rootMessage(failure));
            return structuredFallback(externalUserId, effective, boundedLimit);
        }
    }

    private List<SemanticWardrobeMatch> structuredFallback(
            String externalUserId, WardrobeSearchCriteria criteria, int limit
    ) {
        return wardrobe.activeWardrobeItems(externalUserId, criteria.hasFilters() ? 1000 : limit).stream()
                .filter(criteria::matches)
                .limit(limit)
                .map(item -> new SemanticWardrobeMatch(item, -1d))
                .toList();
    }

    private static Long itemId(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }
}
