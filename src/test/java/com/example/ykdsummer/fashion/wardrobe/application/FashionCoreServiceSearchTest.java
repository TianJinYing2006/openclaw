package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItem;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeSearchCriteria;
import com.example.ykdsummer.fashion.wardrobe.persistence.FashionCoreRepository;
import com.example.ykdsummer.fashion.wardrobe.persistence.FashionSemanticIndexJobRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FashionCoreServiceSearchTest {

    @Test
    void searchesOnlyCurrentUsersItemsAndAppliesCriteriaAsAnIntersection() {
        FashionCoreRepository repository = mock(FashionCoreRepository.class);
        FashionCoreService service = new FashionCoreService(repository);
        String user = "managed:instance:wechat-user";
        WardrobeItem matched = item(1L, "JEANS", "NAVY", List.of("MINIMAL", "COMMUTE"), "RELAXED", "DENIM");
        WardrobeItem wrongStyle = item(2L, "JEANS", "NAVY", List.of("STREET"), "RELAXED", "DENIM");
        WardrobeItem wrongCategory = item(3L, "JACKET", "NAVY", List.of("MINIMAL", "COMMUTE"), "RELAXED", "DENIM");
        when(repository.activeWardrobeItems(user, 1000)).thenReturn(List.of(matched, wrongStyle, wrongCategory));

        List<WardrobeItem> result = service.searchWardrobeItems(user,
                WardrobeSearchCriteria.from("牛仔裤", "深蓝", List.of("简约", "通勤"), "宽松", null,
                        List.of(), List.of(), "牛仔"), 12);

        assertThat(result).extracting(WardrobeItem::id).containsExactly(1L);
        verify(repository).activeWardrobeItems(user, 1000);
    }

    @Test
    void archivesItemAndInvalidatesSemanticIndexWhenChanged() {
        FashionCoreRepository repository = mock(FashionCoreRepository.class);
        FashionSemanticIndexJobRepository jobs = mock(FashionSemanticIndexJobRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FashionSemanticIndexJobRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jobs);
        FashionCoreService service = new FashionCoreService(repository, provider);
        String user = "managed:instance:wechat-user";
        when(repository.archiveWardrobeItem(user, 7L)).thenReturn(true);

        boolean removed = service.archiveWardrobeItem(user, 7L);

        assertThat(removed).isTrue();
        verify(jobs).enqueueDelete(7L);
    }

    @Test
    void skipsSemanticIndexDeleteWhenItemWasAlreadyArchived() {
        FashionCoreRepository repository = mock(FashionCoreRepository.class);
        FashionSemanticIndexJobRepository jobs = mock(FashionSemanticIndexJobRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FashionSemanticIndexJobRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jobs);
        FashionCoreService service = new FashionCoreService(repository, provider);
        String user = "managed:instance:wechat-user";
        when(repository.archiveWardrobeItem(user, 7L)).thenReturn(false);

        boolean removed = service.archiveWardrobeItem(user, 7L);

        assertThat(removed).isFalse();
        verify(jobs, never()).enqueueDelete(7L);
    }

    @Test
    void purgesWardrobeItemAndDeletesStoredAssetsOutsideDatabaseTransaction() {
        FashionCoreRepository repository = mock(FashionCoreRepository.class);
        FashionSemanticIndexJobRepository jobs = mock(FashionSemanticIndexJobRepository.class);
        LocalImageAssetStore store = mock(LocalImageAssetStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FashionSemanticIndexJobRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jobs);
        FashionCoreService service = new FashionCoreService(repository, provider);
        service.setImageStore(store);
        String user = "managed:instance:wechat-user";
        when(repository.purgeWardrobeItem(user, 7L)).thenReturn(List.of("img_asset_1", "img_asset_2"));

        List<String> purged = service.purgeWardrobeItem(user, 7L);

        assertThat(purged).containsExactly("img_asset_1", "img_asset_2");
        verify(jobs).enqueueDelete(7L);
        verify(store).deleteAsset(user, "img_asset_1");
        verify(store).deleteAsset(user, "img_asset_2");
    }

    private static WardrobeItem item(long id, String category, String color, List<String> styles, String fit, String material) {
        Instant now = Instant.now();
        return new WardrobeItem(id, 1L, "instance", category, color, List.of(), styles, fit, "SOLID", List.of(),
                List.of(), material, "ACTIVE", "SUCCEEDED", 1, null, "USER_CONFIRMED", "", now, now);
    }
}
