package com.example.ykdsummer.fashion.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.fashion.domain.WardrobeItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FashionWardrobePreviewSelectionStoreTest {

    @Test
    void resolvesPageAndPositionForOnlyTheCurrentUsersLatestPreview() {
        FashionWardrobePreviewSelectionStore store = new FashionWardrobePreviewSelectionStore();
        store.replace("user-a", List.of(item(11), item(12), item(13), item(14), item(15)));

        assertThat(store.select("user-a", 1, "右下")).map(value -> value.wardrobeItemId()).contains(14L);
        assertThat(store.select("user-a", 2, "左上")).map(value -> value.wardrobeItemId()).contains(15L);
        assertThat(store.select("user-b", 1, "左上")).isEmpty();
        assertThat(store.select("user-a", 2, "右上")).isEmpty();
    }

    private static WardrobeItem item(long id) {
        return new WardrobeItem(id, 1L, "", "T_SHIRT", "WHITE", List.of(), List.of(), "", "", List.of(),
                List.of(), "", "ACTIVE", "SUCCEEDED", 1, null, "USER_CONFIRMED", "", Instant.now(), Instant.now());
    }
}
