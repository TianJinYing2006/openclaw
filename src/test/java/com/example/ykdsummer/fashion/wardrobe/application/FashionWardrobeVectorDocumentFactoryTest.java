package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class FashionWardrobeVectorDocumentFactoryTest {
    private final FashionWardrobeVectorDocumentFactory factory = new FashionWardrobeVectorDocumentFactory();

    @Test
    void buildsStableChineseSearchTextAndOwnerMetadata() {
        WardrobeItem item = item(42L, 7L);

        Document document = factory.document(item);

        assertThat(document.getId()).isEqualTo(factory.documentId(42L));
        assertThat(document.getText())
                .contains("T恤/短袖")
                .contains("灰色")
                .contains("休闲")
                .contains("夏季")
                .contains("面试")
                .contains("棉");
        assertThat(document.getMetadata())
                .containsEntry("scope", "USER_WARDROBE")
                .containsEntry("wardrobeItemId", 42L)
                .containsEntry("appUserId", 7L)
                .containsEntry("instanceId", "instance-a");
        assertThat(factory.contentHash(item)).hasSize(64);
        assertThat(factory.contentHash(item)).isEqualTo(factory.contentHash(item(42L, 7L)));
    }

    private static WardrobeItem item(long id, long appUserId) {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new WardrobeItem(id, appUserId, "instance-a", "T_SHIRT", "GRAY", List.of("WHITE"),
                List.of("CASUAL", "MINIMAL"), "RELAXED", "SOLID", List.of("SUMMER"),
                List.of("INTERVIEW"), "COTTON", "ACTIVE", "SUCCEEDED", 1,
                new BigDecimal("0.95"), "VISION_CONFIRMED", "透气短袖", now, now);
    }
}
