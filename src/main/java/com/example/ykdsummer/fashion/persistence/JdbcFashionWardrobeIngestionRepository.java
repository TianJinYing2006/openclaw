package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.domain.ClothingCandidateDraft;
import com.example.ykdsummer.fashion.domain.ClothingCandidateLabels;
import com.example.ykdsummer.fashion.domain.ClothingCandidateStatus;
import com.example.ykdsummer.fashion.domain.ClothingCompletenessStatus;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.GarmentDraftVersion;
import com.example.ykdsummer.fashion.domain.GarmentCutoutTask;
import com.example.ykdsummer.fashion.domain.GarmentCutoutTaskStatus;
import com.example.ykdsummer.fashion.domain.GarmentCutoutWork;
import com.example.ykdsummer.fashion.identity.FashionUserScope;
import com.example.ykdsummer.fashion.identity.FashionUserScopeResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC state machine for unconfirmed clothing candidates and cutout work. */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcFashionWardrobeIngestionRepository implements FashionWardrobeIngestionRepository {
    private static final String CANDIDATE_COLUMNS = """
            id, app_user_id, instance_id, source_asset_version_id, candidate_index, display_name, category_code,
            color_primary, color_secondary_json, style_tags_json, fit_code, season_tags_json, analysis_attributes_json,
            analysis_confidence, quality_score, completeness_status, retake_guidance, candidate_status,
            current_cutout_asset_version_id, confirmed_wardrobe_item_id, provider, model, prompt_version,
            expires_at, created_at, updated_at
            """;
    private static final String TASK_COLUMNS = """
            id, candidate_id, app_user_id, instance_id, source_asset_version_id, attempt_number, instruction_text,
            task_status, output_asset_version_id, failure_summary, claimed_at, completed_at, expires_at, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FashionUserScopeResolver scopes;
    private final ObjectMapper objectMapper;

    public JdbcFashionWardrobeIngestionRepository(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            FashionUserScopeResolver scopes,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.scopes = scopes;
        this.objectMapper = objectMapper;
    }

    @Override
    public FashionImageAsset requireOwnedImage(String externalUserId, String assetId, Integer version) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        String asset = imageAssetId(assetId);
        int requested = version == null ? 0 : version;
        List<FashionImageAsset> values = requested < 1
                ? jdbc.query("""
                        SELECT id, asset_id, version, mime_type FROM asset_versions
                        WHERE external_user_id = ? AND asset_id = ? AND asset_kind = 'IMAGE'
                        ORDER BY version DESC LIMIT 1
                        """, (rs, row) -> imageAsset(rs), scope.externalUserId(), asset)
                : jdbc.query("""
                        SELECT id, asset_id, version, mime_type FROM asset_versions
                        WHERE external_user_id = ? AND asset_id = ? AND version = ? AND asset_kind = 'IMAGE'
                        """, (rs, row) -> imageAsset(rs), scope.externalUserId(), asset, requested);
        return values.stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Image asset is not available to the current user"));
    }

    @Override
    public List<ClothingCandidate> candidatesForSource(String externalUserId, long sourceAssetVersionId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        requireOwnedImageVersion(scope, sourceAssetVersionId);
        return jdbc.query("SELECT " + CANDIDATE_COLUMNS + " FROM fashion_clothing_candidates "
                        + "WHERE app_user_id = ? AND source_asset_version_id = ? ORDER BY candidate_index",
                (rs, row) -> candidate(rs), scope.appUserId(), sourceAssetVersionId);
    }

    @Override
    public List<ClothingCandidate> createCandidateDrafts(
            String externalUserId, FashionImageAsset source, List<ClothingCandidateDraft> drafts,
            String provider, String model, String promptVersion, Instant expiresAt
    ) {
        if (source == null) throw new IllegalArgumentException("source image is required");
        if (drafts == null || drafts.isEmpty()) return List.of();
        Instant deadline = expiresAt == null ? Instant.now().plus(Duration.ofMinutes(30)) : expiresAt;
        FashionUserScope scope = scopes.resolve(externalUserId);
        requireOwnedImageVersion(scope, source.assetVersionId());
        return transactions.execute(status -> {
            List<ClothingCandidate> existing = jdbc.query("SELECT " + CANDIDATE_COLUMNS + " FROM fashion_clothing_candidates "
                            + "WHERE app_user_id = ? AND source_asset_version_id = ? ORDER BY candidate_index",
                    (rs, row) -> candidate(rs), scope.appUserId(), source.assetVersionId());
            if (!existing.isEmpty()) return existing;
            LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
            for (ClothingCandidateDraft draft : drafts) {
                if (draft == null || !indexes.add(draft.candidateIndex())) continue;
                insertCandidate(scope, source, draft, provider, model, promptVersion, deadline);
            }
            return jdbc.query("SELECT " + CANDIDATE_COLUMNS + " FROM fashion_clothing_candidates "
                            + "WHERE app_user_id = ? AND source_asset_version_id = ? ORDER BY candidate_index",
                    (rs, row) -> candidate(rs), scope.appUserId(), source.assetVersionId());
        });
    }

    @Override
    public Optional<ClothingCandidate> candidate(String externalUserId, String candidateId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return candidate(scope, candidateId);
    }

    @Override
    public List<ClothingCandidate> pendingSelectionCandidates(String externalUserId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return jdbc.query("SELECT " + CANDIDATE_COLUMNS + " FROM fashion_clothing_candidates "
                        + "WHERE app_user_id = ? AND candidate_status = 'PENDING_SELECTION' "
                        + "AND completeness_status = 'READY' AND expires_at > ? ORDER BY updated_at DESC",
                (rs, row) -> candidate(rs), scope.appUserId(), Timestamp.from(Instant.now()));
    }

    @Override
    public List<ClothingCandidate> awaitingFinalConfirmationCandidates(String externalUserId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return jdbc.query("SELECT " + CANDIDATE_COLUMNS + " FROM fashion_clothing_candidates "
                        + "WHERE app_user_id = ? AND candidate_status = 'AWAITING_FINAL_CONFIRMATION' "
                        + "AND expires_at > ? ORDER BY updated_at DESC",
                (rs, row) -> candidate(rs), scope.appUserId(), Timestamp.from(Instant.now()));
    }

    @Override
    public List<ClothingCandidate> activeWorkflowCandidates(String externalUserId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return jdbc.query("SELECT " + CANDIDATE_COLUMNS + " FROM fashion_clothing_candidates "
                        + "WHERE app_user_id = ? AND candidate_status IN "
                        + "('PENDING_SELECTION', 'CUTOUT_SUBMITTED', 'AWAITING_FINAL_CONFIRMATION', "
                        + "'RETAKE_REQUIRED', 'FAILED') AND expires_at > ? "
                        + "ORDER BY updated_at DESC LIMIT 20",
                (rs, row) -> candidate(rs), scope.appUserId(), Timestamp.from(Instant.now()));
    }

    @Override
    public int cancelCandidates(String externalUserId, List<String> candidateIds, Instant cancelledAt) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        List<String> ids = candidateIds == null ? List.of() : candidateIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        if (ids.isEmpty()) throw new IllegalArgumentException("At least one candidate is required");
        if (ids.size() > 8) throw new IllegalArgumentException("At most 8 candidates can be cancelled together");
        return transactions.execute(status -> {
            int cancelled = 0;
            for (String candidateId : ids) {
                ClothingCandidate candidate = requireCandidate(scope, candidateId);
                if (candidate.status() == ClothingCandidateStatus.REJECTED) continue;
                if (candidate.status() == ClothingCandidateStatus.FINAL_CONFIRMED
                        || candidate.status() == ClothingCandidateStatus.EXPIRED) {
                    throw new IllegalStateException("Candidate is no longer cancellable");
                }
                jdbc.update("UPDATE fashion_garment_cutout_tasks SET task_status = 'CANCELLED', completed_at = ? "
                                + "WHERE candidate_id = ? AND app_user_id = ? AND task_status IN ('PENDING', 'PROCESSING')",
                        Timestamp.from(cancelledAt == null ? Instant.now() : cancelledAt),
                        candidate.id(), scope.appUserId());
                cancelled += jdbc.update("UPDATE fashion_clothing_candidates SET candidate_status = 'REJECTED' "
                                + "WHERE id = ? AND app_user_id = ? AND candidate_status <> 'FINAL_CONFIRMED'",
                        candidate.id(), scope.appUserId());
            }
            return cancelled;
        });
    }

    @Override
    public List<GarmentDraftVersion> draftVersions(String externalUserId, String candidateId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        ClothingCandidate candidate = requireCandidate(scope, candidateId);
        List<GarmentDraftVersion> values = jdbc.query("""
                SELECT t.id AS task_id, t.attempt_number, t.instruction_text, t.output_asset_version_id, t.completed_at, t.created_at,
                    a.asset_id, a.version, a.mime_type
                FROM fashion_garment_cutout_tasks t
                JOIN asset_versions a ON a.id = t.output_asset_version_id
                WHERE t.candidate_id = ? AND t.app_user_id = ? AND t.task_status = 'SUCCEEDED'
                    AND t.output_asset_version_id IS NOT NULL
                ORDER BY t.attempt_number, t.id
                """, (rs, row) -> new GarmentDraftVersion(0, rs.getString("task_id"), rs.getString("instruction_text"),
                new FashionImageAsset(rs.getLong("output_asset_version_id"), rs.getString("asset_id"),
                        rs.getInt("version"), rs.getString("mime_type")),
                instant(rs.getTimestamp("completed_at")),
                Long.valueOf(rs.getLong("output_asset_version_id")).equals(candidate.currentCutoutAssetVersionId())),
                candidate.id(), scope.appUserId());
        List<GarmentDraftVersion> numbered = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            GarmentDraftVersion value = values.get(index);
            numbered.add(new GarmentDraftVersion(index + 1, value.taskId(), value.instruction(), value.image(),
                    value.createdAt(), value.current()));
        }
        return List.copyOf(numbered);
    }

    @Override
    public ClothingCandidate updateLabels(String externalUserId, String candidateId, ClothingCandidateLabels labels) {
        if (labels == null) throw new IllegalArgumentException("candidate labels are required");
        FashionUserScope scope = scopes.resolve(externalUserId);
        ClothingCandidate candidate = requireCandidate(scope, candidateId);
        if (candidate.status() != ClothingCandidateStatus.PENDING_SELECTION
                && candidate.status() != ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION) {
            throw new IllegalStateException("Candidate labels can only be changed before final confirmation");
        }
        String category = taxonomyCode(labels.categoryCode());
        requireTaxonomy(category);
        String attributes = mergedAttributes(candidate.analysisAttributesJson(), labels);
        jdbc.update("""
                UPDATE fashion_clothing_candidates
                SET display_name = ?, category_code = ?, color_primary = ?, color_secondary_json = ?,
                    style_tags_json = ?, fit_code = ?, season_tags_json = ?, analysis_attributes_json = ?
                WHERE id = ? AND app_user_id = ?
                """, text(labels.displayName(), 128), category, text(labels.colorPrimary(), 64), jsonArray(labels.secondaryColors()),
                jsonArray(labels.styleTags()), text(labels.fitCode(), 64), jsonArray(labels.seasonTags()), attributes,
                candidate.id(), scope.appUserId());
        return requireCandidate(scope, candidateId);
    }

    @Override
    public void renewCandidateDraft(String externalUserId, String candidateId, Instant expiresAt) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        Instant deadline = expiresAt == null ? Instant.now().plus(Duration.ofMinutes(30)) : expiresAt;
        int changed = jdbc.update("""
                UPDATE fashion_clothing_candidates
                SET expires_at = ?
                WHERE id = ? AND app_user_id = ? AND candidate_status <> 'FINAL_CONFIRMED'
                """, Timestamp.from(deadline), identifier(candidateId, "candidateId"), scope.appUserId());
        if (changed != 1) throw new IllegalStateException("Candidate is not available for draft renewal");
    }

    @Override
    public List<GarmentCutoutTask> submitCutoutTasks(String externalUserId, List<String> candidateIds, String instruction) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        List<String> ids = candidateIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(candidateIds.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::strip).toList()));
        if (ids.isEmpty()) throw new IllegalArgumentException("At least one clothing candidate is required");
        if (ids.size() > 8) throw new IllegalArgumentException("At most 8 clothing candidates can be cut out together");
        return transactions.execute(status -> {
            List<GarmentCutoutTask> tasks = new ArrayList<>();
            for (String candidateId : ids) {
                ClothingCandidate candidate = requireCandidate(scope, candidateId);
                if (candidate.completenessStatus() != ClothingCompletenessStatus.READY) {
                    throw new IllegalStateException("Candidate requires a clearer photo before cutout");
                }
                if (candidate.status() != ClothingCandidateStatus.PENDING_SELECTION) {
                    throw new IllegalStateException("Candidate is not waiting for selection");
                }
                tasks.add(insertCutoutTask(scope, candidate, instruction, candidate.expiresAt()));
                jdbc.update("UPDATE fashion_clothing_candidates SET candidate_status = 'CUTOUT_SUBMITTED', selected_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ?", candidate.id());
            }
            return List.copyOf(tasks);
        });
    }

    @Override
    public GarmentCutoutTask retryCutoutTask(String externalUserId, String candidateId, String instruction, Instant expiresAt) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return transactions.execute(status -> {
            ClothingCandidate candidate = requireCandidate(scope, candidateId);
            if (candidate.completenessStatus() != ClothingCompletenessStatus.READY) {
                throw new IllegalStateException("Candidate requires a clearer photo before retrying cutout");
            }
            if (candidate.status() != ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION
                    && candidate.status() != ClothingCandidateStatus.FAILED) {
                throw new IllegalStateException("Candidate does not have a cutout result that can be retried");
            }
            Instant deadline = expiresAt == null ? Instant.now().plus(Duration.ofMinutes(30)) : expiresAt;
            String nextStatus = candidate.currentCutoutAssetVersionId() == null
                    ? ClothingCandidateStatus.CUTOUT_SUBMITTED.name() : ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION.name();
            jdbc.update("UPDATE fashion_clothing_candidates SET candidate_status = ?, expires_at = ? WHERE id = ?",
                    nextStatus, Timestamp.from(deadline), candidate.id());
            ClothingCandidate refreshed = requireCandidate(scope, candidate.id());
            return insertCutoutTask(scope, refreshed, instruction, deadline);
        });
    }

    @Override
    public GarmentCutoutTask reviseDraftTask(String externalUserId, String candidateId, int sourceVersionNumber,
                                              String instruction, Instant expiresAt) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return transactions.execute(status -> {
            ClothingCandidate candidate = requireCandidate(scope, candidateId);
            if (candidate.status() != ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION
                    || candidate.currentCutoutAssetVersionId() == null) {
                throw new IllegalStateException("A completed garment draft is required before editing it");
            }
            List<GarmentDraftVersion> versions = draftVersions(externalUserId, candidate.id());
            GarmentDraftVersion source = versions.stream()
                    .filter(value -> value.versionNumber() == sourceVersionNumber)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Selected garment draft version is not available"));
            Instant deadline = expiresAt == null ? Instant.now().plus(Duration.ofMinutes(30)) : expiresAt;
            jdbc.update("UPDATE fashion_clothing_candidates SET expires_at = ? WHERE id = ?",
                    Timestamp.from(deadline), candidate.id());
            ClothingCandidate refreshed = requireCandidate(scope, candidate.id());
            return insertCutoutTask(scope, refreshed, source.image().assetVersionId(), instruction, deadline);
        });
    }

    @Override
    public Optional<GarmentCutoutTask> latestCutoutTask(String externalUserId, String candidateId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        List<GarmentCutoutTask> values = jdbc.query("SELECT " + TASK_COLUMNS
                        + " FROM fashion_garment_cutout_tasks WHERE candidate_id = ? AND app_user_id = ? "
                        + "ORDER BY attempt_number DESC LIMIT 1",
                (rs, row) -> task(rs), identifier(candidateId, "candidateId"), scope.appUserId());
        return values.stream().findFirst();
    }

    @Override
    public List<String> pendingCutoutTaskIds(Instant now, int limit) {
        Instant time = now == null ? Instant.now() : now;
        return jdbc.query("""
                SELECT id FROM fashion_garment_cutout_tasks
                WHERE task_status = 'PENDING' AND expires_at > ?
                ORDER BY created_at, id LIMIT ?
                """, (rs, row) -> rs.getString("id"), Timestamp.from(time), boundedLimit(limit, 16));
    }

    @Override
    public int recoverInterruptedCutoutTasks() {
        // 进程重启后 PROCESSING 的抠图任务回到 PENDING 由调度器重新认领；
        // 已过期任务随后由 expireUnconfirmedDrafts 统一标记 EXPIRED。
        return jdbc.update("""
                UPDATE fashion_garment_cutout_tasks
                SET task_status = 'PENDING', claimed_at = NULL
                WHERE task_status = 'PROCESSING'
                """);
    }

    @Override
    public Optional<GarmentCutoutWork> claimCutoutTask(String taskId, Instant now) {
        String id = identifier(taskId, "taskId");
        Instant time = now == null ? Instant.now() : now;
        int changed = jdbc.update("""
                UPDATE fashion_garment_cutout_tasks
                SET task_status = 'PROCESSING', claimed_at = ?
                WHERE id = ? AND task_status = 'PENDING' AND expires_at > ?
                """, Timestamp.from(time), id, Timestamp.from(time));
        if (changed == 0) return Optional.empty();
        List<GarmentCutoutWork> values = jdbc.query("""
                SELECT t.id AS task_id, t.candidate_id, t.app_user_id AS task_app_user_id, t.instance_id AS task_instance_id,
                    t.source_asset_version_id AS task_source_asset_version_id, t.attempt_number, t.instruction_text,
                    t.task_status, t.output_asset_version_id, t.failure_summary, t.claimed_at, t.completed_at,
                    t.expires_at AS task_expires_at, t.created_at AS task_created_at, t.updated_at AS task_updated_at,
                    a.external_user_id, a.asset_id, a.version, a.mime_type,
                    c.id AS candidate_id_value, c.app_user_id AS candidate_app_user_id, c.instance_id AS candidate_instance_id,
                    c.source_asset_version_id AS candidate_source_asset_version_id, c.candidate_index, c.display_name,
                    c.category_code, c.color_primary, c.color_secondary_json, c.style_tags_json, c.fit_code, c.season_tags_json,
                    c.analysis_attributes_json, c.analysis_confidence, c.quality_score, c.completeness_status, c.retake_guidance,
                    c.candidate_status, c.current_cutout_asset_version_id, c.confirmed_wardrobe_item_id, c.provider, c.model,
                    c.prompt_version, c.expires_at AS candidate_expires_at, c.created_at AS candidate_created_at,
                    c.updated_at AS candidate_updated_at
                FROM fashion_garment_cutout_tasks t
                JOIN fashion_clothing_candidates c ON c.id = t.candidate_id
                JOIN asset_versions a ON a.id = t.source_asset_version_id
                WHERE t.id = ?
                """, (rs, row) -> work(rs), id);
        return values.stream().findFirst();
    }

    @Override
    public void completeCutoutTask(String taskId, long outputAssetVersionId, Instant completedAt) {
        Instant time = completedAt == null ? Instant.now() : completedAt;
        transactions.executeWithoutResult(status -> {
            GarmentCutoutTask task = requireTask(taskId);
            if (task.status() != GarmentCutoutTaskStatus.PROCESSING) return;
            if (!task.expiresAt().isAfter(time)) {
                jdbc.update("UPDATE fashion_garment_cutout_tasks SET task_status = 'EXPIRED', completed_at = ? WHERE id = ?",
                        Timestamp.from(time), task.id());
                jdbc.update("""
                        UPDATE fashion_clothing_candidates
                        SET candidate_status = CASE
                            WHEN current_cutout_asset_version_id IS NULL THEN 'EXPIRED'
                            ELSE 'AWAITING_FINAL_CONFIRMATION'
                        END
                        WHERE id = ?
                        """, task.candidateId());
                return;
            }
            requireOwnedOutputAsset(task.appUserId(), outputAssetVersionId);
            jdbc.update("UPDATE fashion_garment_cutout_tasks SET task_status = 'SUCCEEDED', output_asset_version_id = ?, completed_at = ? "
                    + "WHERE id = ?", outputAssetVersionId, Timestamp.from(time), task.id());
            jdbc.update("UPDATE fashion_clothing_candidates SET candidate_status = 'AWAITING_FINAL_CONFIRMATION', "
                    + "current_cutout_asset_version_id = ? WHERE id = ? AND candidate_status <> 'FINAL_CONFIRMED'",
                    outputAssetVersionId, task.candidateId());
        });
    }

    @Override
    public void failCutoutTask(String taskId, String failureSummary, Instant completedAt) {
        Instant time = completedAt == null ? Instant.now() : completedAt;
        transactions.executeWithoutResult(status -> {
            GarmentCutoutTask task = requireTask(taskId);
            if (task.status() != GarmentCutoutTaskStatus.PROCESSING) return;
            String failure = text(failureSummary, 512);
            jdbc.update("UPDATE fashion_garment_cutout_tasks SET task_status = 'FAILED', failure_summary = ?, completed_at = ? WHERE id = ?",
                    failure, Timestamp.from(time), task.id());
            jdbc.update("""
                    UPDATE fashion_clothing_candidates
                    SET candidate_status = CASE
                        WHEN current_cutout_asset_version_id IS NULL THEN 'FAILED'
                        ELSE 'AWAITING_FINAL_CONFIRMATION'
                    END
                    WHERE id = ? AND candidate_status <> 'FINAL_CONFIRMED'
                    """, task.candidateId());
        });
    }

    @Override
    public FashionImageAsset requireOwnedImageVersion(String externalUserId, long assetVersionId) {
        return requireOwnedImageVersion(scopes.resolve(externalUserId), assetVersionId);
    }

    @Override
    public void markCandidateConfirmed(String externalUserId, String candidateId, long selectedAssetVersionId,
                                       long wardrobeItemId, Instant confirmedAt) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        Instant time = confirmedAt == null ? Instant.now() : confirmedAt;
        requireOwnedWardrobeItem(scope.appUserId(), wardrobeItemId);
        Integer selected = jdbc.queryForObject("""
                SELECT COUNT(*) FROM fashion_garment_cutout_tasks
                WHERE candidate_id = ? AND app_user_id = ? AND task_status = 'SUCCEEDED'
                    AND output_asset_version_id = ?
                """, Integer.class, identifier(candidateId, "candidateId"), scope.appUserId(), selectedAssetVersionId);
        if (selected == null || selected != 1) throw new IllegalArgumentException("Selected garment draft version is not available");
        int changed = jdbc.update("""
                UPDATE fashion_clothing_candidates
                SET candidate_status = 'FINAL_CONFIRMED', current_cutout_asset_version_id = ?,
                    confirmed_wardrobe_item_id = ?, final_confirmed_at = ?
                WHERE id = ? AND app_user_id = ? AND candidate_status = 'AWAITING_FINAL_CONFIRMATION'
                    AND expires_at > ?
                """, selectedAssetVersionId, wardrobeItemId, Timestamp.from(time), identifier(candidateId, "candidateId"),
                scope.appUserId(), Timestamp.from(time));
        if (changed != 1) throw new IllegalStateException("Candidate is not ready for final confirmation");
    }

    @Override
    public int expireUnconfirmedDrafts(Instant now) {
        Instant time = now == null ? Instant.now() : now;
        return transactions.execute(status -> {
            jdbc.update("UPDATE fashion_garment_cutout_tasks SET task_status = 'EXPIRED', completed_at = ? "
                            + "WHERE task_status IN ('PENDING', 'PROCESSING') AND expires_at <= ?",
                    Timestamp.from(time), Timestamp.from(time));
            jdbc.update("UPDATE fashion_clothing_candidates SET candidate_status = 'EXPIRED' "
                    + "WHERE candidate_status <> 'FINAL_CONFIRMED' AND expires_at <= ?", Timestamp.from(time));
            return jdbc.update("DELETE FROM fashion_clothing_candidates "
                    + "WHERE candidate_status = 'EXPIRED' AND expires_at <= ?", Timestamp.from(time));
        });
    }

    private void insertCandidate(FashionUserScope scope, FashionImageAsset source, ClothingCandidateDraft draft,
                                 String provider, String model, String promptVersion, Instant deadline) {
        String category = taxonomyCode(draft.categoryCode());
        requireTaxonomy(category);
        ClothingCompletenessStatus completeness = draft.completenessStatus() == null
                ? ClothingCompletenessStatus.RETAKE_REQUIRED : draft.completenessStatus();
        ClothingCandidateStatus candidateStatus = completeness == ClothingCompletenessStatus.READY
                ? ClothingCandidateStatus.PENDING_SELECTION : ClothingCandidateStatus.RETAKE_REQUIRED;
        jdbc.update("""
                INSERT INTO fashion_clothing_candidates(
                    id, app_user_id, instance_id, source_asset_version_id, candidate_index, display_name, category_code,
                    color_primary, color_secondary_json, style_tags_json, fit_code, season_tags_json, analysis_attributes_json,
                    analysis_confidence, quality_score, completeness_status, retake_guidance, candidate_status,
                    provider, model, prompt_version, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), scope.appUserId(), scope.instanceId(), source.assetVersionId(),
                Math.max(0, Math.min(draft.candidateIndex(), 127)), text(draft.displayName(), 128), category,
                text(draft.colorPrimary(), 64), jsonArray(draft.secondaryColors()), jsonArray(draft.styleTags()),
                text(draft.fitCode(), 64), jsonArray(draft.seasonTags()), jsonObject(draft.analysisAttributesJson()),
                score(draft.analysisConfidence()), score(draft.qualityScore()), completeness.name(), text(draft.retakeGuidance(), 512),
                candidateStatus.name(), text(provider, 64), text(model, 128), text(promptVersion, 64), Timestamp.from(deadline));
    }

    private GarmentCutoutTask insertCutoutTask(FashionUserScope scope, ClothingCandidate candidate, String instruction, Instant deadline) {
        return insertCutoutTask(scope, candidate, candidate.sourceAssetVersionId(), instruction, deadline);
    }

    private GarmentCutoutTask insertCutoutTask(FashionUserScope scope, ClothingCandidate candidate, long sourceAssetVersionId,
                                                String instruction, Instant deadline) {
        requireOwnedOutputAsset(scope.appUserId(), sourceAssetVersionId);
        int attempt = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_number), 0) + 1 "
                + "FROM fashion_garment_cutout_tasks WHERE candidate_id = ?", Integer.class, candidate.id());
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO fashion_garment_cutout_tasks(
                    id, candidate_id, app_user_id, instance_id, source_asset_version_id, attempt_number, instruction_text,
                    task_status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, id, candidate.id(), scope.appUserId(), scope.instanceId(), sourceAssetVersionId, attempt,
                text(instruction, 1000), Timestamp.from(deadline));
        return requireTask(id);
    }

    private Optional<ClothingCandidate> candidate(FashionUserScope scope, String candidateId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT " + CANDIDATE_COLUMNS + " FROM fashion_clothing_candidates "
                    + "WHERE id = ? AND app_user_id = ?", (rs, row) -> candidate(rs), identifier(candidateId, "candidateId"), scope.appUserId()));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private ClothingCandidate requireCandidate(FashionUserScope scope, String candidateId) {
        return candidate(scope, candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Clothing candidate is not available to the current user"));
    }

    private GarmentCutoutTask requireTask(String taskId) {
        try {
            return jdbc.queryForObject("SELECT " + TASK_COLUMNS + " FROM fashion_garment_cutout_tasks WHERE id = ?",
                    (rs, row) -> task(rs), identifier(taskId, "taskId"));
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("Garment cutout task does not exist");
        }
    }

    private FashionImageAsset requireOwnedImageVersion(FashionUserScope scope, long assetVersionId) {
        try {
            return jdbc.queryForObject("""
                    SELECT id, asset_id, version, mime_type FROM asset_versions
                    WHERE id = ? AND external_user_id = ? AND asset_kind = 'IMAGE'
                    """, (rs, row) -> imageAsset(rs), assetVersionId, scope.externalUserId());
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("Image asset is not available to the current user");
        }
    }

    private void requireOwnedOutputAsset(long appUserId, long assetVersionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM asset_versions asset
                JOIN app_users user_scope ON user_scope.external_user_id = asset.external_user_id
                WHERE asset.id = ? AND asset.asset_kind = 'IMAGE' AND user_scope.id = ?
                """, Integer.class, assetVersionId, appUserId);
        if (count == null || count != 1) throw new IllegalArgumentException("Cutout output image asset does not exist");
    }

    private void requireOwnedWardrobeItem(long appUserId, long wardrobeItemId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM fashion_wardrobe_items WHERE id = ? AND app_user_id = ?",
                Integer.class, wardrobeItemId, appUserId);
        if (count == null || count != 1) throw new IllegalArgumentException("Wardrobe item is not owned by the current user");
    }

    private void requireTaxonomy(String categoryCode) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM fashion_taxonomy_nodes WHERE code = ? AND status = 'ACTIVE'",
                Integer.class, categoryCode);
        if (count == null || count != 1) throw new IllegalArgumentException("Unknown Fashion taxonomy code: " + categoryCode);
    }

    private ClothingCandidate candidate(ResultSet rs) throws java.sql.SQLException {
        long cutout = rs.getLong("current_cutout_asset_version_id");
        Long cutoutId = rs.wasNull() ? null : cutout;
        long wardrobe = rs.getLong("confirmed_wardrobe_item_id");
        Long wardrobeId = rs.wasNull() ? null : wardrobe;
        return new ClothingCandidate(rs.getString("id"), rs.getLong("app_user_id"), rs.getString("instance_id"),
                rs.getLong("source_asset_version_id"), rs.getInt("candidate_index"), rs.getString("display_name"),
                rs.getString("category_code"), rs.getString("color_primary"), stringList(rs.getString("color_secondary_json")),
                stringList(rs.getString("style_tags_json")), rs.getString("fit_code"), stringList(rs.getString("season_tags_json")),
                rs.getString("analysis_attributes_json"), rs.getBigDecimal("analysis_confidence"), rs.getBigDecimal("quality_score"),
                ClothingCompletenessStatus.valueOf(rs.getString("completeness_status")), rs.getString("retake_guidance"),
                ClothingCandidateStatus.valueOf(rs.getString("candidate_status")), cutoutId, wardrobeId,
                rs.getString("provider"), rs.getString("model"), rs.getString("prompt_version"),
                instant(rs.getTimestamp("expires_at")), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private GarmentCutoutTask task(ResultSet rs) throws java.sql.SQLException {
        long output = rs.getLong("output_asset_version_id");
        Long outputId = rs.wasNull() ? null : output;
        return new GarmentCutoutTask(rs.getString("id"), rs.getString("candidate_id"), rs.getLong("app_user_id"),
                rs.getString("instance_id"), rs.getLong("source_asset_version_id"), rs.getInt("attempt_number"),
                rs.getString("instruction_text"), GarmentCutoutTaskStatus.valueOf(rs.getString("task_status")), outputId,
                rs.getString("failure_summary"), instant(rs.getTimestamp("claimed_at")), instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("expires_at")), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private GarmentCutoutWork work(ResultSet rs) throws java.sql.SQLException {
        GarmentCutoutTask task = new GarmentCutoutTask(rs.getString("task_id"), rs.getString("candidate_id"),
                rs.getLong("task_app_user_id"), rs.getString("task_instance_id"), rs.getLong("task_source_asset_version_id"),
                rs.getInt("attempt_number"), rs.getString("instruction_text"), GarmentCutoutTaskStatus.valueOf(rs.getString("task_status")),
                nullableLong(rs, "output_asset_version_id"), rs.getString("failure_summary"), instant(rs.getTimestamp("claimed_at")),
                instant(rs.getTimestamp("completed_at")), instant(rs.getTimestamp("task_expires_at")),
                instant(rs.getTimestamp("task_created_at")), instant(rs.getTimestamp("task_updated_at")));
        ClothingCandidate candidate = new ClothingCandidate(rs.getString("candidate_id_value"), rs.getLong("candidate_app_user_id"),
                rs.getString("candidate_instance_id"), rs.getLong("candidate_source_asset_version_id"), rs.getInt("candidate_index"),
                rs.getString("display_name"), rs.getString("category_code"), rs.getString("color_primary"),
                stringList(rs.getString("color_secondary_json")), stringList(rs.getString("style_tags_json")), rs.getString("fit_code"),
                stringList(rs.getString("season_tags_json")), rs.getString("analysis_attributes_json"),
                rs.getBigDecimal("analysis_confidence"), rs.getBigDecimal("quality_score"),
                ClothingCompletenessStatus.valueOf(rs.getString("completeness_status")), rs.getString("retake_guidance"),
                ClothingCandidateStatus.valueOf(rs.getString("candidate_status")), nullableLong(rs, "current_cutout_asset_version_id"),
                nullableLong(rs, "confirmed_wardrobe_item_id"), rs.getString("provider"), rs.getString("model"),
                rs.getString("prompt_version"), instant(rs.getTimestamp("candidate_expires_at")),
                instant(rs.getTimestamp("candidate_created_at")), instant(rs.getTimestamp("candidate_updated_at")));
        FashionImageAsset source = new FashionImageAsset(task.sourceAssetVersionId(), rs.getString("asset_id"),
                rs.getInt("version"), rs.getString("mime_type"));
        return new GarmentCutoutWork(rs.getString("external_user_id"), task, candidate, source);
    }

    private FashionImageAsset imageAsset(ResultSet rs) throws java.sql.SQLException {
        return new FashionImageAsset(rs.getLong("id"), rs.getString("asset_id"), rs.getInt("version"), rs.getString("mime_type"));
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            node.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) values.add(value.asText()); });
            return List.copyOf(values);
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private String jsonArray(List<String> values) {
        List<String> clean = new ArrayList<>(new LinkedHashSet<>((values == null ? List.<String>of() : values).stream()
                .map(value -> text(value, 128)).filter(value -> !value.isBlank()).toList()));
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Fashion labels", exception);
        }
    }

    private String jsonObject(String value) {
        try {
            JsonNode node = objectMapper.readTree(value == null || value.isBlank() ? "{}" : value);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("analysisAttributesJson must be a JSON object");
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("analysisAttributesJson must be valid JSON", exception);
        }
    }

    private String mergedAttributes(String raw, ClothingCandidateLabels labels) {
        try {
            JsonNode parsed = objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            ObjectNode attributes = parsed != null && parsed.isObject()
                    ? ((ObjectNode) parsed).deepCopy() : objectMapper.createObjectNode();
            if (!text(labels.material(), 128).isBlank()) attributes.put("material", text(labels.material(), 128));
            if (!text(labels.patternCode(), 64).isBlank()) attributes.put("patternCode", text(labels.patternCode(), 64));
            if (labels.occasionTags() != null) attributes.set("occasionTags", objectMapper.readTree(jsonArray(labels.occasionTags())));
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not update clothing candidate attributes", exception);
        }
    }

    private static String taxonomyCode(String value) {
        String code = text(value, 64).toUpperCase(Locale.ROOT);
        return code.isBlank() ? "UNKNOWN" : code;
    }

    private static String imageAssetId(String value) {
        String asset = text(value, 64);
        if (!asset.matches("img_[a-zA-Z0-9_]{12,64}")) throw new IllegalArgumentException("imageAssetId must be an image asset id");
        return asset;
    }

    private static String identifier(String value, String field) {
        String id = text(value, 64);
        if (id.isBlank()) throw new IllegalArgumentException(field + " is required");
        return id;
    }

    private static int boundedLimit(int value, int fallback) { return Math.max(1, Math.min(value <= 0 ? fallback : value, 100)); }
    private static BigDecimal score(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ZERO;
        return value;
    }
    private static Long nullableLong(ResultSet rs, String field) throws java.sql.SQLException {
        long value = rs.getLong(field);
        return rs.wasNull() ? null : value;
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String text(String value, int limit) {
        String clean = value == null ? "" : value.replace('\u0000', ' ').strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
}
