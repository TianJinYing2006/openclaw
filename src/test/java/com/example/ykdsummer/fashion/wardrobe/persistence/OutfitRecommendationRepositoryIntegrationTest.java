package com.example.ykdsummer.fashion.wardrobe.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRecommendationRequest;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRecommendationResult;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRenderStatus;
import com.example.ykdsummer.fashion.wardrobe.identity.FashionUserScope;
import com.example.ykdsummer.fashion.wardrobe.identity.FashionUserScopeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit opt-in verification for V17, user isolation, rank hydration, and interrupted-render recovery. */
@EnabledIfEnvironmentVariable(named = "PERSISTENCE_INTEGRATION", matches = "true")
class OutfitRecommendationRepositoryIntegrationTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private OutfitRecommendationRepository repository;
    private FashionUserScopeResolver scopes;
    private String userA;
    private String userB;
    private String runId;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv().getOrDefault("PERSISTENCE_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3306/ykd_summer?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"));
        config.setUsername(System.getenv().getOrDefault("PERSISTENCE_USERNAME", "root"));
        config.setPassword(requiredEnvironment("PERSISTENCE_PASSWORD"));
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        scopes = new FashionUserScopeResolver(jdbc, transactions);
        repository = new JdbcOutfitRecommendationRepository(jdbc, transactions, scopes, new ObjectMapper());
        String suffix = UUID.randomUUID().toString();
        userA = "outfit-integration-a-" + suffix;
        userB = "outfit-integration-b-" + suffix;
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) {
            if (runId != null) {
                jdbc.update("DELETE FROM fashion_outfit_recommendation_runs WHERE id = ?", runId);
            }
            if (userA != null) cleanupUser(userA);
            if (userB != null) cleanupUser(userB);
        }
        if (dataSource != null) dataSource.close();
    }

    @Test
    void persistsAndRehydratesRankedOptionsWhileKeepingUsersIsolated() {
        FashionUserScope owner = scopes.resolve(userA);
        scopes.resolve(userB);
        SavedItem top = item(owner.appUserId(), userA, "白色短袖", "TOP", "T_SHIRT", "WHITE");
        SavedItem bottom = item(owner.appUserId(), userA, "深蓝直筒裤", "BOTTOM", "STRAIGHT_PANTS", "NAVY");
        runId = UUID.randomUUID().toString();
        String optionId = UUID.randomUUID().toString();
        OutfitRecommendationRequest request = new OutfitRecommendationRequest(
                userA, top.itemId(), List.of("通勤"), List.of("夏季"),
                "武汉 30°C", List.of("简约"), "明天", 3);
        OutfitRecommendationResult.Option option = new OutfitRecommendationResult.Option(
                optionId, 1, List.of(
                resultItem(top, "TOP", "白色短袖", "T_SHIRT", "WHITE"),
                resultItem(bottom, "BOTTOM", "深蓝直筒裤", "STRAIGHT_PANTS", "NAVY")),
                88.5d, Map.of("evidence", 0.92d, "color", 0.86d), List.of(),
                "白色短袖 + 深蓝直筒裤", OutfitRenderStatus.SUBMITTED, "", 0, "");
        OutfitRecommendationResult expected = new OutfitRecommendationResult(
                runId, top.itemId(), List.of(option), null, Instant.now());

        repository.save(request, expected);

        assertThat(repository.latest(userA)).get().satisfies(saved -> {
            assertThat(saved.recommendationId()).isEqualTo(runId);
            assertThat(saved.options()).singleElement().satisfies(savedOption -> {
                assertThat(savedOption.rank()).isEqualTo(1);
                assertThat(savedOption.displaySummary()).isEqualTo("白色短袖 + 深蓝直筒裤");
                assertThat(savedOption.items()).extracting(OutfitRecommendationResult.Item::wardrobeItemId)
                        .containsExactly(top.itemId(), bottom.itemId());
            });
        });
        assertThat(repository.latest(userB)).isEmpty();
        assertThat(repository.recentlyRecommendedItemIds(userA, 20))
                .contains(top.itemId(), bottom.itemId());
        assertThat(repository.pendingRenderOptionIds(16)).contains(optionId);
        assertThat(repository.claimRender(optionId, Instant.now())).get()
                .satisfies(work -> assertThat(work.items()).hasSize(2));
        assertThat(repository.claimRender(optionId, Instant.now())).isEmpty();

        OutfitRecommendationRequest wrongOwner = new OutfitRecommendationRequest(
                userB, top.itemId(), List.of(), List.of(), "", List.of(), "", 1);
        OutfitRecommendationResult wrongResult = new OutfitRecommendationResult(
                UUID.randomUUID().toString(), top.itemId(), List.of(), null, Instant.now());
        assertThatThrownBy(() -> repository.save(wrongOwner, wrongResult))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not owned");
    }

    private SavedItem item(
            long appUserId,
            String externalUserId,
            String name,
            String parent,
            String category,
            String color
    ) {
        jdbc.update("""
                INSERT INTO fashion_wardrobe_items(
                    app_user_id, display_name, parent_category_code, category_code, color_primary,
                    item_status, analysis_status, analysis_version, attribute_confidence,
                    source, annotation_schema_version, attributes_json)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'SUCCEEDED', 1, 1.00,
                    'USER_CONFIRMED', '1.0.0', JSON_OBJECT())
                """, appUserId, name, parent, category, color);
        Long itemId = jdbc.queryForObject("""
                SELECT id FROM fashion_wardrobe_items
                WHERE app_user_id = ? AND display_name = ? ORDER BY id DESC LIMIT 1
                """, Long.class, appUserId, name);
        String assetId = "img_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("""
                INSERT INTO asset_versions(
                    external_user_id, asset_id, version, asset_kind, storage_provider,
                    object_key, mime_type, source, prompt, tags, created_at)
                VALUES (?, ?, 1, 'IMAGE', 'local', ?, 'image/png', 'integration', '', '', ?)
                """, externalUserId, assetId, "integration/" + assetId + ".png",
                java.sql.Timestamp.from(Instant.now()));
        Long assetVersionId = jdbc.queryForObject("""
                SELECT id FROM asset_versions
                WHERE external_user_id = ? AND asset_id = ? AND version = 1
                """, Long.class, externalUserId, assetId);
        jdbc.update("""
                INSERT INTO fashion_wardrobe_item_assets(
                    wardrobe_item_id, asset_version_id, asset_role, is_primary)
                VALUES (?, ?, 'PRIMARY', TRUE)
                """, itemId, assetVersionId);
        return new SavedItem(itemId, assetVersionId, assetId);
    }

    private static OutfitRecommendationResult.Item resultItem(
            SavedItem saved,
            String role,
            String name,
            String category,
            String color
    ) {
        return new OutfitRecommendationResult.Item(saved.itemId(), role, name, category, color,
                saved.assetVersionId(), saved.assetId(), 1);
    }

    private void cleanupUser(String externalUserId) {
        Optional<Long> userId = jdbc.query("SELECT id FROM app_users WHERE external_user_id = ?",
                (rs, row) -> rs.getLong("id"), externalUserId).stream().findFirst();
        userId.ifPresent(id -> {
            jdbc.update("""
                    DELETE recommendation_run FROM fashion_outfit_recommendation_runs recommendation_run
                    WHERE recommendation_run.app_user_id = ?
                    """, id);
            jdbc.update("""
                    DELETE link FROM fashion_wardrobe_item_assets link
                    JOIN fashion_wardrobe_items item ON item.id = link.wardrobe_item_id
                    WHERE item.app_user_id = ?
                    """, id);
            jdbc.update("DELETE FROM fashion_wardrobe_items WHERE app_user_id = ?", id);
        });
        jdbc.update("DELETE FROM asset_versions WHERE external_user_id = ?", externalUserId);
        jdbc.update("DELETE FROM app_users WHERE external_user_id = ?", externalUserId);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for integration tests");
        return value;
    }

    private record SavedItem(long itemId, long assetVersionId, String assetId) { }
}
