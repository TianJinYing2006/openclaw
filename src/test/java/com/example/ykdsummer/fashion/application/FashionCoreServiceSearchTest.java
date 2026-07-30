package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import com.example.ykdsummer.fashion.persistence.FashionCoreRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

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

    private static WardrobeItem item(long id, String category, String color, List<String> styles, String fit, String material) {
        Instant now = Instant.now();
        return new WardrobeItem(id, 1L, "instance", category, color, List.of(), styles, fit, "SOLID", List.of(),
                List.of(), material, "ACTIVE", "SUCCEEDED", 1, null, "USER_CONFIRMED", "", now, now);
    }
}
