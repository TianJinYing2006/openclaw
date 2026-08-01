package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.fashion.config.OutfitRecommendationProperties;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.OutfitRecommendationRequest;
import com.example.ykdsummer.fashion.domain.OutfitRecommendationResult;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.persistence.OutfitRecommendationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutfitRecommendationServiceTest {

    @Test
    void scopesAnchorImagesCandidatesAndSnapshotToTheCurrentUser() {
        FashionCoreService wardrobe = mock(FashionCoreService.class);
        FashionReferenceSemanticSearchService publicSearch = mock(FashionReferenceSemanticSearchService.class);
        OutfitRecommendationEngine engine = mock(OutfitRecommendationEngine.class);
        OutfitRecommendationRepository repository = mock(OutfitRecommendationRepository.class);
        OutfitRecommendationProperties properties = new OutfitRecommendationProperties();
        OutfitRecommendationService service = new OutfitRecommendationService(
                wardrobe, publicSearch, engine, repository, properties);
        WardrobeItem anchor = wardrobe(42L, "白色短袖", "TOP", "T_SHIRT", "WHITE");
        WardrobeItem bottom = wardrobe(43L, "深蓝直筒裤", "BOTTOM", "STRAIGHT_PANTS", "NAVY");
        FashionImageAsset topImage = new FashionImageAsset(101L, "img_top", 1, "image/png");
        FashionImageAsset bottomImage = new FashionImageAsset(102L, "img_bottom", 1, "image/png");
        OutfitRecommendationRequest request = new OutfitRecommendationRequest(
                "managed:instance-a:wechat-user", 42L, List.of("通勤"), List.of("夏季"),
                "武汉 30°C", List.of("简约"), "明天", 3);
        when(wardrobe.ownedWardrobeItem(request.externalUserId(), 42L)).thenReturn(Optional.of(anchor));
        when(wardrobe.activeWardrobeItems(request.externalUserId(), 1000)).thenReturn(List.of(anchor, bottom));
        when(wardrobe.primaryWardrobeImages(request.externalUserId(), List.of(42L, 43L)))
                .thenReturn(Map.of(42L, topImage, 43L, bottomImage));
        when(wardrobe.preferences(request.externalUserId())).thenReturn(List.of());
        when(publicSearch.searchGarments(anyString(), any(), anyInt())).thenReturn(List.of());
        when(repository.recentlyRecommendedItemIds(request.externalUserId(), 60)).thenReturn(Set.of());
        Map<String, WardrobeItem> selected = new LinkedHashMap<>();
        selected.put("TOP", anchor);
        selected.put("BOTTOM", bottom);
        OutfitRecommendationEngine.Candidate candidate = new OutfitRecommendationEngine.Candidate(
                selected, 87d, Map.of("evidence", 0.9d, "color", 0.85d), List.of());
        when(engine.recommend(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OutfitRecommendationEngine.EngineResult(List.of(candidate), null));
        when(repository.save(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        OutfitRecommendationResult result = service.recommend(request);

        assertThat(result.options()).singleElement().satisfies(option -> {
            assertThat(option.items()).extracting(OutfitRecommendationResult.Item::wardrobeItemId)
                    .containsExactly(42L, 43L);
            assertThat(option.displaySummary()).isEqualTo("白色短袖 + 深蓝直筒裤");
        });
        verify(wardrobe).ownedWardrobeItem(request.externalUserId(), 42L);
        verify(wardrobe).primaryWardrobeImages(request.externalUserId(), List.of(42L, 43L));
        verify(repository).save(request, result);
    }

    @Test
    void rejectsAnAnchorThatIsNotOwnedByTheCurrentUserBeforeAnyPublicSearch() {
        FashionCoreService wardrobe = mock(FashionCoreService.class);
        FashionReferenceSemanticSearchService publicSearch = mock(FashionReferenceSemanticSearchService.class);
        OutfitRecommendationEngine engine = mock(OutfitRecommendationEngine.class);
        OutfitRecommendationRepository repository = mock(OutfitRecommendationRepository.class);
        OutfitRecommendationService service = new OutfitRecommendationService(
                wardrobe, publicSearch, engine, repository, new OutfitRecommendationProperties());
        OutfitRecommendationRequest request = new OutfitRecommendationRequest(
                "wechat-user-b", 42L, List.of(), List.of(), "", List.of(), "", 3);
        when(wardrobe.ownedWardrobeItem("wechat-user-b", 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recommend(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
        verify(publicSearch, never()).searchGarments(anyString(), any(), anyInt());
        verify(repository, never()).save(any(), any());
    }

    private static WardrobeItem wardrobe(
            long id, String name, String parent, String category, String color
    ) {
        Instant now = Instant.now();
        return new WardrobeItem(id, 1L, "instance", name, parent, category, color, List.of(),
                List.of("MINIMAL"), "REGULAR", "SOLID", List.of("SUMMER"), List.of("COMMUTE"),
                "COTTON", "ACTIVE", "SUCCEEDED", 1, BigDecimal.ONE,
                "USER_CONFIRMED", "", "1.0.0", "{}", now, now);
    }
}
