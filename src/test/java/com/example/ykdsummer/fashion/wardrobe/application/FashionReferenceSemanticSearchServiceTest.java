package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.fashion.wardrobe.config.FashionSemanticProperties;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionReferenceGarment;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.wardrobe.domain.SemanticReferenceGarmentMatch;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeSearchCriteria;
import com.example.ykdsummer.fashion.wardrobe.persistence.FashionReferenceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class FashionReferenceSemanticSearchServiceTest {

    @Test
    void fallsBackToStructuredMysqlGarmentsWhenQdrantIsUnavailable() {
        FashionReferenceRepository repository = mock(FashionReferenceRepository.class);
        VectorStore vectors = mock(VectorStore.class);
        FashionReferenceSemanticSearchService service = new FashionReferenceSemanticSearchService(
                repository, vectors, new FashionSemanticProperties());
        FashionReferenceLook look = look();
        when(vectors.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("qdrant unavailable"));
        when(repository.activeLooks(2000)).thenReturn(List.of(look));

        List<SemanticReferenceGarmentMatch> result = service.searchGarments(
                "浅灰色宽松短袖", WardrobeSearchCriteria.from(
                        "上衣", null, List.of(), null, null, List.of(), List.of(), null), 10);

        assertThat(result).singleElement().satisfies(match -> {
            assertThat(match.look().id()).isEqualTo(8L);
            assertThat(match.garment().categoryCode()).isEqualTo("TOP");
            assertThat(match.score()).isEqualTo(-1d);
        });
    }

    private static FashionReferenceLook look() {
        FashionReferenceGarment top = new FashionReferenceGarment(
                1L, 8L, 1, "浅灰色宽松短袖", "TOP", "T_SHIRT", "MENS", "LIGHT_GRAY",
                List.of(), List.of(), List.of("MINIMAL"), "RELAXED", "SOLID", "H_LINE",
                "REGULAR", List.of("COTTON"), List.of("SUMMER"), List.of("DAILY"), 1,
                "FULL", new BigDecimal("0.95"), new BigDecimal("0.95"), "{}");
        return new FashionReferenceLook(8L, "look-8", "浅灰短袖穿搭", "img_public0001", 1,
                "image/png", "look.png", "", "LOCAL_IMPORT", "LOCAL_DEVELOPMENT_ONLY",
                "a".repeat(64), "", "1.0.0", "{}", "ACTIVE", List.of(top),
                Instant.now(), Instant.now());
    }
}
