package com.example.ykdsummer.fashion.wardrobe.persistence;

import com.example.ykdsummer.fashion.wardrobe.application.FashionCoreService;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingAnalysis;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingAnalysisDraft;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidateDraft;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidateStatus;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCompletenessStatus;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionPersonTemplate;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionPersonTemplateStatus;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionPreferenceUpdate;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionProfileUpdate;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionUserProfile;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItem;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItemDraft;
import com.example.ykdsummer.fashion.wardrobe.domain.PersonTemplateAssessment;
import com.example.ykdsummer.fashion.wardrobe.identity.FashionUserScope;
import com.example.ykdsummer.fashion.wardrobe.identity.FashionUserScopeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit opt-in local MySQL test. It validates V6 and removes all data it creates. */
@EnabledIfEnvironmentVariable(named = "PERSISTENCE_INTEGRATION", matches = "true")
class FashionCoreRepositoryIntegrationTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private FashionCoreRepository repository;
    private FashionWardrobeIngestionRepository ingestion;
    private FashionPersonTemplateRepository personTemplates;
    private FashionCoreService fashion;
    private String firstInstanceId;
    private String secondInstanceId;
    private Long firstPlatformUserId;
    private Long secondPlatformUserId;
    private String firstExternalUser;
    private String secondExternalUser;
    private Long firstAppUserId;
    private Long secondAppUserId;
    private Long firstAssetVersionId;
    private String firstAssetId;
    private String firstCandidateId;
    private String firstCutoutTaskId;

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

        firstInstanceId = UUID.randomUUID().toString();
        secondInstanceId = UUID.randomUUID().toString();
        firstPlatformUserId = createPlatformUser("fashion-first-");
        secondPlatformUserId = createPlatformUser("fashion-second-");
        jdbc.update("INSERT INTO bot_instances(id, platform_user_id) VALUES (?, ?)", firstInstanceId, firstPlatformUserId);
        jdbc.update("INSERT INTO bot_instances(id, platform_user_id) VALUES (?, ?)", secondInstanceId, secondPlatformUserId);
        String rawWechatIdentity = "fashion-user@im.wechat";
        firstExternalUser = "managed:" + firstInstanceId + ':' + rawWechatIdentity;
        secondExternalUser = "managed:" + secondInstanceId + ':' + rawWechatIdentity;

        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        FashionUserScopeResolver resolver = new FashionUserScopeResolver(jdbc, transactions);
        repository = new JdbcFashionCoreRepository(jdbc, transactions, resolver, new ObjectMapper());
        ingestion = new JdbcFashionWardrobeIngestionRepository(jdbc, transactions, resolver, new ObjectMapper());
        personTemplates = new JdbcFashionPersonTemplateRepository(jdbc, transactions, resolver);
        fashion = new FashionCoreService(repository);
        FashionUserScope firstScope = repository.resolveUser(firstExternalUser);
        FashionUserScope secondScope = repository.resolveUser(secondExternalUser);
        firstAppUserId = firstScope.appUserId();
        secondAppUserId = secondScope.appUserId();
        firstAssetVersionId = createOwnedImageAsset(firstScope);
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) {
            if (firstAppUserId != null || secondAppUserId != null) {
                jdbc.update("DELETE FROM fashion_person_templates WHERE app_user_id IN (?, ?)", safeId(firstAppUserId), safeId(secondAppUserId));
                jdbc.update("DELETE FROM fashion_garment_cutout_tasks WHERE app_user_id IN (?, ?)", safeId(firstAppUserId), safeId(secondAppUserId));
                jdbc.update("DELETE FROM fashion_clothing_candidates WHERE app_user_id IN (?, ?)", safeId(firstAppUserId), safeId(secondAppUserId));
                jdbc.update("DELETE FROM fashion_clothing_analyses WHERE app_user_id IN (?, ?)", safeId(firstAppUserId), safeId(secondAppUserId));
                jdbc.update("""
                        DELETE link FROM fashion_wardrobe_item_assets link
                        JOIN fashion_wardrobe_items item ON item.id = link.wardrobe_item_id
                        WHERE item.app_user_id IN (?, ?)
                        """, safeId(firstAppUserId), safeId(secondAppUserId));
                jdbc.update("DELETE FROM fashion_wardrobe_items WHERE app_user_id IN (?, ?)", safeId(firstAppUserId), safeId(secondAppUserId));
                jdbc.update("DELETE FROM fashion_user_preferences WHERE app_user_id IN (?, ?)", safeId(firstAppUserId), safeId(secondAppUserId));
                jdbc.update("DELETE FROM fashion_user_profiles WHERE app_user_id IN (?, ?)", safeId(firstAppUserId), safeId(secondAppUserId));
            }
            if (firstExternalUser != null || secondExternalUser != null) {
                jdbc.update("DELETE FROM asset_versions WHERE external_user_id IN (?, ?)", safe(firstExternalUser), safe(secondExternalUser));
                jdbc.update("DELETE FROM app_users WHERE external_user_id IN (?, ?)", safe(firstExternalUser), safe(secondExternalUser));
            }
            if (firstInstanceId != null || secondInstanceId != null) {
                jdbc.update("DELETE FROM bot_instances WHERE id IN (?, ?)", safe(firstInstanceId), safe(secondInstanceId));
            }
            if (firstPlatformUserId != null || secondPlatformUserId != null) {
                jdbc.update("DELETE FROM platform_users WHERE id IN (?, ?)", safeId(firstPlatformUserId), safeId(secondPlatformUserId));
            }
        }
        if (dataSource != null) dataSource.close();
    }

    @Test
    void persistsFashionCoreDataAndBlocksCrossUserAccess() {
        FashionUserProfile profile = repository.saveProfile(firstExternalUser, new FashionProfileUpdate(
                "UNSPECIFIED", "minimal and relaxed", new BigDecimal("300"), new BigDecimal("800"),
                List.of("COMMUTE", "INTERVIEW"), 60, true));
        assertEquals(firstAppUserId.longValue(), profile.appUserId());
        assertEquals(List.of("COMMUTE", "INTERVIEW"), profile.commonOccasions());
        assertTrue(profile.privacyConsentAt() != null);

        repository.upsertPreference(firstExternalUser, new FashionPreferenceUpdate(
                "STYLE", "MINIMAL", "POSITIVE", new BigDecimal("8"), new BigDecimal("0.9"),
                "USER_DECLARED", "I prefer simple outfits"));
        assertEquals(1, repository.preferences(firstExternalUser).size());
        assertTrue(repository.preferences(secondExternalUser).isEmpty());

        assertEquals(firstAssetVersionId, repository.findOwnedImageAssetVersion(firstExternalUser, firstAssetId, 1).orElseThrow());
        assertTrue(repository.findOwnedImageAssetVersion(secondExternalUser, firstAssetId, 1).isEmpty());

        WardrobeItem item = fashion.addWardrobeItemWithImage(firstExternalUser, new WardrobeItemDraft(
                "T_SHIRT", "BLACK", List.of("GRAY"), List.of("MINIMAL"), "RELAXED", "SOLID",
                List.of("SUMMER"), List.of("COMMUTE"), "COTTON", "USER_UPLOAD", "test garment", new BigDecimal("0.86")),
                firstAssetId, 1);
        ClothingAnalysis analysis = repository.recordAnalysis(firstExternalUser, firstAssetVersionId, new ClothingAnalysisDraft(
                item.id(), "T_SHIRT", "{\"category\":\"T_SHIRT\",\"colors\":[\"BLACK\"]}", new BigDecimal("0.91"),
                "SUCCEEDED", "test-provider", "test-model", "fashion-v1", ""));

        assertEquals(item.id(), analysis.wardrobeItemId());
        assertEquals("T_SHIRT", analysis.categoryCode());
        assertEquals(1, repository.activeWardrobeItems(firstExternalUser, 10).size());
        assertEquals(firstAssetVersionId, repository.primaryWardrobeImage(firstExternalUser, item.id()).orElseThrow().assetVersionId());
        assertTrue(repository.primaryWardrobeImage(secondExternalUser, item.id()).isEmpty());
        assertFalse(repository.activeWardrobeItems(secondExternalUser, 10).stream().anyMatch(value -> value.id() == item.id()));
        assertThrows(IllegalArgumentException.class,
                () -> repository.attachWardrobeAsset(secondExternalUser, item.id(), firstAssetVersionId, "PRIMARY", true));
    }

    @Test
    void persistsExpiringCandidateAndIndependentCutoutTask() {
        firstCandidateId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(600);
        jdbc.update("""
                INSERT INTO fashion_clothing_candidates(
                    id, app_user_id, instance_id, source_asset_version_id, candidate_index, display_name,
                    category_code, color_primary, style_tags_json, fit_code, season_tags_json,
                    analysis_attributes_json, analysis_confidence, quality_score, completeness_status,
                    retake_guidance, candidate_status, expires_at)
                VALUES (?, ?, ?, ?, 0, 'black t-shirt', 'T_SHIRT', 'BLACK', JSON_ARRAY('MINIMAL'),
                    'RELAXED', JSON_ARRAY('SUMMER'), JSON_OBJECT('visibility', 'FULL'), 0.91, 0.88,
                    'READY', '', 'PENDING_SELECTION', ?)
                """, firstCandidateId, firstAppUserId, firstInstanceId, firstAssetVersionId, Timestamp.from(expiresAt));

        assertEquals("PENDING_SELECTION", jdbc.queryForObject(
                "SELECT candidate_status FROM fashion_clothing_candidates WHERE id = ?", String.class, firstCandidateId));
        assertEquals("READY", jdbc.queryForObject(
                "SELECT completeness_status FROM fashion_clothing_candidates WHERE id = ?", String.class, firstCandidateId));
        assertTrue(jdbc.queryForObject("SELECT expires_at >= ? FROM fashion_clothing_candidates WHERE id = ?",
                Boolean.class, Timestamp.from(expiresAt.minusSeconds(2)), firstCandidateId));

        firstCutoutTaskId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO fashion_garment_cutout_tasks(
                    id, candidate_id, app_user_id, instance_id, source_asset_version_id, attempt_number,
                    instruction_text, task_status, expires_at)
                VALUES (?, ?, ?, ?, ?, 1, 'keep the full garment silhouette', 'PENDING', ?)
                """, firstCutoutTaskId, firstCandidateId, firstAppUserId, firstInstanceId, firstAssetVersionId,
                Timestamp.from(expiresAt));

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT task_status FROM fashion_garment_cutout_tasks WHERE id = ?", String.class, firstCutoutTaskId));
        assertEquals(firstCandidateId, jdbc.queryForObject(
                "SELECT candidate_id FROM fashion_garment_cutout_tasks WHERE id = ?", String.class, firstCutoutTaskId));
    }

    @Test
    void persistsCandidateCutoutWorkflowUntilFinalConfirmation() {
        FashionImageAsset source = ingestion.requireOwnedImage(firstExternalUser, firstAssetId, 1);
        ClothingCandidateDraft draft = new ClothingCandidateDraft(0, "black t-shirt", "T_SHIRT", "BLACK",
                List.of("GRAY"), List.of("MINIMAL"), "RELAXED", List.of("SUMMER"),
                "{\"patternCode\":\"SOLID\",\"occasionTags\":[\"COMMUTE\"]}", new BigDecimal("0.91"),
                new BigDecimal("0.88"), ClothingCompletenessStatus.READY, "");
        List<ClothingCandidate> candidates = ingestion.createCandidateDrafts(firstExternalUser, source, List.of(draft),
                "test-vision", "test-model", "fashion-v1", Instant.now().plusSeconds(600));
        ClothingCandidate candidate = candidates.getFirst();
        assertEquals(ClothingCandidateStatus.PENDING_SELECTION, candidate.status());

        var task = ingestion.submitCutoutTasks(firstExternalUser, List.of(candidate.id()), "keep full hem").getFirst();
        assertTrue(ingestion.pendingCutoutTaskIds(Instant.now(), 10).contains(task.id()));
        var work = ingestion.claimCutoutTask(task.id(), Instant.now()).orElseThrow();
        assertEquals(candidate.id(), work.candidate().id());
        assertEquals(source.assetVersionId(), work.sourceImage().assetVersionId());

        String cutoutAssetId = "img_cutout" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Long cutoutAssetVersionId = createOwnedImageAsset(repository.resolveUser(firstExternalUser), cutoutAssetId);
        ingestion.completeCutoutTask(task.id(), cutoutAssetVersionId, Instant.now());
        ClothingCandidate awaiting = ingestion.candidate(firstExternalUser, candidate.id()).orElseThrow();
        assertEquals(ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION, awaiting.status());
        assertEquals(cutoutAssetVersionId, awaiting.currentCutoutAssetVersionId());

        WardrobeItem item = fashion.addWardrobeItemWithImage(firstExternalUser, new WardrobeItemDraft(
                "T_SHIRT", "BLACK", List.of("GRAY"), List.of("MINIMAL"), "RELAXED", "SOLID",
                List.of("SUMMER"), List.of("COMMUTE"), "", "USER_CONFIRMED", "", new BigDecimal("0.91")),
                cutoutAssetId, 1);
        ingestion.markCandidateConfirmed(firstExternalUser, candidate.id(), item.id(), Instant.now());
        assertEquals(ClothingCandidateStatus.FINAL_CONFIRMED,
                ingestion.candidate(firstExternalUser, candidate.id()).orElseThrow().status());
    }

    @Test
    void restoresActiveWorkflowAfterRepositoryRecreationAndCancelsItIdempotently() {
        FashionImageAsset source = ingestion.requireOwnedImage(firstExternalUser, firstAssetId, 1);
        ClothingCandidate candidate = ingestion.createCandidateDrafts(firstExternalUser, source, List.of(
                new ClothingCandidateDraft(0, "gray printed t-shirt", "T_SHIRT", "GRAY", List.of("BLACK"),
                        List.of("CASUAL"), "RELAXED", List.of("SUMMER"),
                        "{\"material\":\"COTTON\",\"patternCode\":\"PRINTED\"}", new BigDecimal("0.91"),
                        new BigDecimal("0.88"), ClothingCompletenessStatus.READY, "")),
                "test-vision", "test-model", "fashion-v1", Instant.now().plusSeconds(1_800)).getFirst();
        assertEquals(List.of(candidate.id()), ingestion.pendingSelectionCandidates(firstExternalUser).stream()
                .map(ClothingCandidate::id).toList());
        assertTrue(ingestion.pendingSelectionCandidates(secondExternalUser).isEmpty());

        TransactionTemplate restartedTransactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        FashionUserScopeResolver restartedResolver = new FashionUserScopeResolver(jdbc, restartedTransactions);
        FashionWardrobeIngestionRepository restartedRepository =
                new JdbcFashionWardrobeIngestionRepository(jdbc, restartedTransactions, restartedResolver, new ObjectMapper());
        assertEquals(List.of(candidate.id()), restartedRepository.activeWorkflowCandidates(firstExternalUser).stream()
                .map(ClothingCandidate::id).toList());

        var task = restartedRepository.submitCutoutTasks(firstExternalUser, List.of(candidate.id()), "keep print").getFirst();
        assertEquals(task.id(), restartedRepository.latestCutoutTask(firstExternalUser, candidate.id()).orElseThrow().id());
        assertTrue(restartedRepository.latestCutoutTask(secondExternalUser, candidate.id()).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> restartedRepository.submitCutoutTasks(firstExternalUser, List.of(candidate.id()), "duplicate"));
        assertThrows(IllegalArgumentException.class,
                () -> restartedRepository.cancelCandidates(secondExternalUser, List.of(candidate.id()), Instant.now()));
        assertEquals(1, restartedRepository.cancelCandidates(firstExternalUser, List.of(candidate.id()), Instant.now()));
        assertEquals(ClothingCandidateStatus.REJECTED,
                restartedRepository.candidate(firstExternalUser, candidate.id()).orElseThrow().status());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT task_status FROM fashion_garment_cutout_tasks WHERE id = ?", String.class, task.id()));
        assertEquals(0, restartedRepository.cancelCandidates(firstExternalUser, List.of(candidate.id()), Instant.now()));
        assertTrue(restartedRepository.activeWorkflowCandidates(firstExternalUser).isEmpty());

        jdbc.update("UPDATE fashion_clothing_candidates SET candidate_status = 'PENDING_SELECTION', expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), candidate.id());
        assertTrue(restartedRepository.pendingSelectionCandidates(firstExternalUser).isEmpty());
        assertTrue(restartedRepository.activeWorkflowCandidates(firstExternalUser).isEmpty());
    }

    @Test
    void keepsMultipleCompletedDraftVersionsAndAllowsConfirmingAnOlderChoice() {
        FashionImageAsset source = ingestion.requireOwnedImage(firstExternalUser, firstAssetId, 1);
        ClothingCandidate candidate = ingestion.createCandidateDrafts(firstExternalUser, source, List.of(
                new ClothingCandidateDraft(0, "black t-shirt", "T_SHIRT", "BLACK", List.of(), List.of("MINIMAL"),
                        "RELAXED", List.of("SUMMER"), "{}", new BigDecimal("0.91"), new BigDecimal("0.88"),
                        ClothingCompletenessStatus.READY, "")), "test-vision", "test-model", "fashion-v1",
                Instant.now().plusSeconds(1_800)).getFirst();

        var initialTask = ingestion.submitCutoutTasks(firstExternalUser, List.of(candidate.id()), "").getFirst();
        ingestion.claimCutoutTask(initialTask.id(), Instant.now()).orElseThrow();
        String firstDraftAssetId = "img_draft" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long firstDraftAssetVersionId = createOwnedImageAsset(repository.resolveUser(firstExternalUser), firstDraftAssetId);
        ingestion.completeCutoutTask(initialTask.id(), firstDraftAssetVersionId, Instant.now());

        var revisionTask = ingestion.reviseDraftTask(firstExternalUser, candidate.id(), 1, "make the hem longer",
                Instant.now().plusSeconds(1_800));
        assertEquals(revisionTask.id(), ingestion.latestCutoutTask(firstExternalUser, candidate.id()).orElseThrow().id());
        assertTrue(ingestion.latestCutoutTask(secondExternalUser, candidate.id()).isEmpty());
        var revisionWork = ingestion.claimCutoutTask(revisionTask.id(), Instant.now()).orElseThrow();
        assertEquals(firstDraftAssetVersionId, revisionWork.sourceImage().assetVersionId());
        String secondDraftAssetId = "img_draft" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long secondDraftAssetVersionId = createOwnedImageAsset(repository.resolveUser(firstExternalUser), secondDraftAssetId);
        ingestion.completeCutoutTask(revisionTask.id(), secondDraftAssetVersionId, Instant.now());

        var versions = ingestion.draftVersions(firstExternalUser, candidate.id());
        assertEquals(2, versions.size());
        assertEquals(firstDraftAssetVersionId, versions.get(0).image().assetVersionId());
        assertFalse(versions.get(0).current());
        assertEquals(secondDraftAssetVersionId, versions.get(1).image().assetVersionId());
        assertTrue(versions.get(1).current());

        WardrobeItem item = fashion.addWardrobeItemWithImage(firstExternalUser, new WardrobeItemDraft(
                "T_SHIRT", "BLACK", List.of(), List.of("MINIMAL"), "RELAXED", "SOLID", List.of("SUMMER"),
                List.of(), "", "USER_CONFIRMED", "", new BigDecimal("0.91")), firstDraftAssetId, 1);
        ingestion.markCandidateConfirmed(firstExternalUser, candidate.id(), firstDraftAssetVersionId, item.id(), Instant.now());

        ClothingCandidate confirmed = ingestion.candidate(firstExternalUser, candidate.id()).orElseThrow();
        assertEquals(ClothingCandidateStatus.FINAL_CONFIRMED, confirmed.status());
        assertEquals(firstDraftAssetVersionId, confirmed.currentCutoutAssetVersionId());
    }

    @Test
    void keepsTryOnTemplatesPrivateAndMakesOnlyOneTemplateActivePerUser() {
        FashionImageAsset firstSource = ingestion.requireOwnedImage(firstExternalUser, firstAssetId, 1);
        PersonTemplateAssessment ready = new PersonTemplateAssessment(FashionPersonTemplateStatus.READY,
                "单人全身清晰入镜", "", new BigDecimal("0.93"));

        FashionPersonTemplate first = personTemplates.saveActive(firstExternalUser, firstSource, "通勤模板", ready);
        assertTrue(first.active());
        assertEquals(first.id(), personTemplates.active(firstExternalUser).orElseThrow().id());
        assertTrue(personTemplates.active(secondExternalUser).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> personTemplates.activate(secondExternalUser, first.id()));

        String secondAssetId = "img_person_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        createOwnedImageAsset(repository.resolveUser(firstExternalUser), secondAssetId);
        FashionImageAsset secondSource = ingestion.requireOwnedImage(firstExternalUser, secondAssetId, 1);
        FashionPersonTemplate second = personTemplates.saveActive(firstExternalUser, secondSource, "约会模板", ready);

        assertTrue(second.active());
        assertFalse(personTemplates.list(firstExternalUser, 10).stream()
                .filter(value -> value.id().equals(first.id())).findFirst().orElseThrow().active());
        assertEquals(second.id(), personTemplates.active(firstExternalUser).orElseThrow().id());
    }

    private Long createPlatformUser(String prefix) {
        String username = prefix + UUID.randomUUID();
        jdbc.update("INSERT INTO platform_users(username, remark) VALUES (?, '')", username);
        return jdbc.queryForObject("SELECT id FROM platform_users WHERE username = ?", Long.class, username);
    }

    private Long createOwnedImageAsset(FashionUserScope scope) {
        String assetId = "img_fashion_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        firstAssetId = assetId;
        return createOwnedImageAsset(scope, assetId);
    }

    private Long createOwnedImageAsset(FashionUserScope scope, String assetId) {
        jdbc.update("""
                INSERT INTO asset_versions(external_user_id, platform_user_id, instance_id, asset_id, version, asset_kind,
                    storage_provider, object_key, mime_type, source, prompt, tags, created_at)
                VALUES (?, ?, ?, ?, 1, 'IMAGE', 'local', ?, 'image/png', 'test', '', '', ?)
                """, scope.externalUserId(), scope.platformUserId(), scope.instanceId(), assetId,
                "fashion-test/" + assetId + ".png", Timestamp.from(Instant.now()));
        return jdbc.queryForObject("SELECT id FROM asset_versions WHERE external_user_id = ? AND asset_id = ?",
                Long.class, scope.externalUserId(), assetId);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for integration tests");
        return value;
    }

    private static long safeId(Long value) { return value == null ? -1L : value; }
    private static String safe(String value) { return value == null ? "" : value; }
}
