package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.fashion.domain.SemanticWardrobeMatch;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeItemDraft;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "FASHION_SEMANTIC_LIVE", matches = "true")
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.persistence.enabled=true",
                "app.persistence.redis.enabled=false",
                "app.admin.enabled=false",
                "app.fashion.semantic.enabled=true",
                "app.fashion.semantic.dispatch-interval=1s"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class FashionSemanticLiveIntegrationTest {
    @Autowired private FashionCoreService fashion;
    @Autowired private FashionSemanticSearchService semanticSearch;
    @Autowired private FashionWardrobeVectorDocumentFactory documents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("fashionVectorStore") private VectorStore vectorStore;

    @Test
    void asynchronouslyIndexesSearchesAndIsolatesOneUsersWardrobe() throws Exception {
        String owner = "semantic-live-owner-" + UUID.randomUUID();
        String otherUser = "semantic-live-other-" + UUID.randomUUID();
        WardrobeItem item = null;
        long ownerId = 0L;
        long otherId = 0L;
        try {
            item = fashion.addWardrobeItem(owner, new WardrobeItemDraft(
                    "T_SHIRT", "LIGHT_GRAY", List.of("WHITE"), List.of("CASUAL", "MINIMAL"),
                    "RELAXED", "SOLID", List.of("SUMMER"), List.of("INTERVIEW"), "COTTON",
                    "LIVE_TEST", "适合炎热天气的透气浅色面试上衣", new BigDecimal("0.98")));
            ownerId = item.appUserId();
            otherId = fashion.profile(otherUser).appUserId();

            awaitSucceeded(item.id(), Duration.ofSeconds(30));

            WardrobeSearchCriteria none = WardrobeSearchCriteria.from(
                    null, null, List.of(), null, null, List.of(), List.of(), null);
            List<SemanticWardrobeMatch> ownerMatches = semanticSearch.search(
                    owner, "明天很热，要参加面试，找一件透气的浅色上衣", none, 5);
            List<SemanticWardrobeMatch> otherMatches = semanticSearch.search(
                    otherUser, "明天很热，要参加面试，找一件透气的浅色上衣", none, 5);

            long itemId = item.id();
            assertThat(ownerMatches).anyMatch(match -> match.item().id() == itemId && match.score() >= 0d);
            assertThat(otherMatches).noneMatch(match -> match.item().id() == itemId);
        } finally {
            if (item != null) {
                vectorStore.delete(List.of(documents.documentId(item.id())));
                jdbc.update("DELETE FROM fashion_semantic_index_jobs WHERE wardrobe_item_id = ?", item.id());
                jdbc.update("DELETE FROM fashion_wardrobe_items WHERE id = ?", item.id());
            }
            if (ownerId > 0) jdbc.update("DELETE FROM app_users WHERE id = ?", ownerId);
            if (otherId > 0) jdbc.update("DELETE FROM app_users WHERE id = ?", otherId);
        }
    }

    private void awaitSucceeded(long wardrobeItemId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<String> statuses = jdbc.query(
                    "SELECT status FROM fashion_semantic_index_jobs WHERE wardrobe_item_id = ?",
                    (rs, row) -> rs.getString("status"), wardrobeItemId);
            if (statuses.contains("SUCCEEDED")) return;
            if (statuses.contains("FAILED")) throw new AssertionError("Semantic index job failed");
            Thread.sleep(250L);
        }
        throw new AssertionError("Semantic index job did not finish within " + timeout);
    }
}
