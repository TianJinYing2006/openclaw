package com.wechatbot.fashion.wardrobe.persistence;

import com.wechatbot.fashion.wardrobe.domain.FashionImageAsset;
import com.wechatbot.fashion.wardrobe.domain.OutfitRecommendationRequest;
import com.wechatbot.fashion.wardrobe.domain.OutfitRecommendationResult;
import com.wechatbot.fashion.wardrobe.domain.OutfitRenderStatus;
import com.wechatbot.fashion.wardrobe.domain.OutfitRenderWork;
import com.wechatbot.fashion.wardrobe.identity.FashionUserScope;
import com.wechatbot.fashion.wardrobe.identity.FashionUserScopeResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC persistence keeps recommendation decisions and image work restart-safe and user-scoped. */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcOutfitRecommendationRepository implements OutfitRecommendationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FashionUserScopeResolver scopes;
    private final ObjectMapper objectMapper;

    public JdbcOutfitRecommendationRepository(
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
    public OutfitRecommendationResult save(
            OutfitRecommendationRequest request,
            OutfitRecommendationResult result
    ) {
        if (request == null || result == null) throw new IllegalArgumentException("Recommendation is required");
        FashionUserScope scope = scopes.resolve(request.externalUserId());
        if (result.anchorWardrobeItemId() != request.anchorWardrobeItemId()) {
            throw new IllegalArgumentException("Recommendation anchor does not match request");
        }
        return transactions.execute(status -> {
            requireOwnedItem(scope.appUserId(), result.anchorWardrobeItemId());
            String runStatus = result.options().isEmpty() ? "READY" : "RENDERING";
            jdbc.update("""
                    INSERT INTO fashion_outfit_recommendation_runs(
                        id, app_user_id, instance_id, anchor_wardrobe_item_id, request_context_json,
                        requested_limit, recommendation_status, missing_item_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, identifier(result.recommendationId()), scope.appUserId(), nullable(scope.instanceId()),
                    result.anchorWardrobeItemId(), json(context(request)), request.maxResults(), runStatus,
                    result.missingItem() == null ? null : json(result.missingItem()));
            for (OutfitRecommendationResult.Option option : result.options()) {
                jdbc.update("""
                        INSERT INTO fashion_outfit_recommendation_options(
                            id, recommendation_run_id, option_rank, total_score, display_summary,
                            score_breakdown_json, evidence_json, render_status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED')
                        """, identifier(option.optionId()), identifier(result.recommendationId()), option.rank(),
                        option.totalScore(), text(option.displaySummary(), 512), json(option.scoreBreakdown()),
                        json(option.evidence()));
                for (OutfitRecommendationResult.Item item : option.items()) {
                    requireOwnedSource(scope.appUserId(), item);
                    jdbc.update("""
                            INSERT INTO fashion_outfit_recommendation_items(
                                recommendation_option_id, wardrobe_item_id, item_role, source_asset_version_id)
                            VALUES (?, ?, ?, ?)
                            """, option.optionId(), item.wardrobeItemId(), role(item.role()),
                            item.sourceAssetVersionId());
                }
            }
            return result;
        });
    }

    @Override
    public Set<Long> recentlyRecommendedItemIds(String externalUserId, int limit) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        List<Long> values = jdbc.query("""
                SELECT item.wardrobe_item_id
                FROM fashion_outfit_recommendation_items item
                JOIN fashion_outfit_recommendation_options option_record
                    ON option_record.id = item.recommendation_option_id
                JOIN fashion_outfit_recommendation_runs run_record
                    ON run_record.id = option_record.recommendation_run_id
                WHERE run_record.app_user_id = ?
                ORDER BY run_record.created_at DESC, option_record.option_rank, item.item_role
                LIMIT ?
                """, (rs, row) -> rs.getLong("wardrobe_item_id"), scope.appUserId(),
                Math.max(1, Math.min(limit, 500)));
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    @Override
    public List<String> pendingRenderOptionIds(int limit) {
        return jdbc.query("""
                SELECT id FROM fashion_outfit_recommendation_options
                WHERE render_status = 'SUBMITTED'
                ORDER BY created_at, option_rank, id LIMIT ?
                """, (rs, row) -> rs.getString("id"), Math.max(1, Math.min(limit, 16)));
    }

    @Override
    public Optional<OutfitRenderWork> claimRender(String optionId, Instant claimedAt) {
        String id = identifier(optionId);
        Instant now = claimedAt == null ? Instant.now() : claimedAt;
        int changed = jdbc.update("""
                UPDATE fashion_outfit_recommendation_options
                SET render_status = 'PROCESSING', claimed_at = ?, render_attempt_count = render_attempt_count + 1
                WHERE id = ? AND render_status = 'SUBMITTED'
                """, Timestamp.from(now), id);
        if (changed == 0) return Optional.empty();
        List<RenderRow> rows = jdbc.query("""
                SELECT user_record.external_user_id, run_record.id AS recommendation_id,
                    option_record.id AS option_id, option_record.option_rank, option_record.display_summary,
                    item.wardrobe_item_id, item.item_role, wardrobe.display_name,
                    asset.id AS asset_version_id, asset.asset_id, asset.version, asset.mime_type
                FROM fashion_outfit_recommendation_options option_record
                JOIN fashion_outfit_recommendation_runs run_record
                    ON run_record.id = option_record.recommendation_run_id
                JOIN app_users user_record ON user_record.id = run_record.app_user_id
                JOIN fashion_outfit_recommendation_items item
                    ON item.recommendation_option_id = option_record.id
                JOIN fashion_wardrobe_items wardrobe ON wardrobe.id = item.wardrobe_item_id
                JOIN asset_versions asset ON asset.id = item.source_asset_version_id
                WHERE option_record.id = ?
                ORDER BY CASE item.item_role WHEN 'OUTERWEAR' THEN 1 WHEN 'TOP' THEN 2 ELSE 3 END
                """, (rs, row) -> new RenderRow(rs.getString("external_user_id"),
                rs.getString("recommendation_id"), rs.getString("option_id"), rs.getInt("option_rank"),
                rs.getString("display_summary"), new OutfitRenderWork.SourceItem(
                rs.getLong("wardrobe_item_id"), rs.getString("item_role"), rs.getString("display_name"),
                new FashionImageAsset(rs.getLong("asset_version_id"), rs.getString("asset_id"),
                        rs.getInt("version"), rs.getString("mime_type")))), id);
        if (rows.isEmpty()) {
            failRender(id, "推荐方案没有可用的单品图片", now);
            return Optional.empty();
        }
        RenderRow first = rows.getFirst();
        return Optional.of(new OutfitRenderWork(first.externalUserId(), first.recommendationId(), first.optionId(),
                first.rank(), first.displaySummary(), rows.stream().map(RenderRow::source).toList()));
    }

    @Override
    public void completeRender(
            String optionId, String outputAssetId, int outputVersion,
            OutfitRenderStatus status, Instant completedAt
    ) {
        if (status != OutfitRenderStatus.SUCCEEDED && status != OutfitRenderStatus.FALLBACK) {
            throw new IllegalArgumentException("Completed render status must be SUCCEEDED or FALLBACK");
        }
        Instant now = completedAt == null ? Instant.now() : completedAt;
        transactions.executeWithoutResult(transaction -> {
            OptionOwner owner = optionOwner(identifier(optionId));
            Long outputAssetVersionId = jdbc.queryForObject("""
                    SELECT asset.id
                    FROM asset_versions asset
                    JOIN app_users user_record ON user_record.external_user_id = asset.external_user_id
                    WHERE user_record.id = ? AND asset.asset_id = ? AND asset.version = ?
                      AND asset.asset_kind = 'IMAGE'
                    """, Long.class, owner.appUserId(), text(outputAssetId, 64), Math.max(1, outputVersion));
            if (outputAssetVersionId == null) {
                throw new IllegalStateException("Outfit output asset is not owned by recommendation user");
            }
            jdbc.update("""
                    UPDATE fashion_outfit_recommendation_options
                    SET render_status = ?, output_asset_version_id = ?, completed_at = ?, failure_summary = ''
                    WHERE id = ? AND render_status = 'PROCESSING'
                    """, status.name(), outputAssetVersionId, Timestamp.from(now), owner.optionId());
            refreshRunStatus(owner.runId());
        });
    }

    @Override
    public void failRender(String optionId, String failureSummary, Instant completedAt) {
        Instant now = completedAt == null ? Instant.now() : completedAt;
        transactions.executeWithoutResult(transaction -> {
            OptionOwner owner = optionOwner(identifier(optionId));
            jdbc.update("""
                    UPDATE fashion_outfit_recommendation_options
                    SET render_status = 'FAILED', completed_at = ?, failure_summary = ?
                    WHERE id = ? AND render_status IN ('PROCESSING', 'SUBMITTED')
                    """, Timestamp.from(now), text(failureSummary, 512), owner.optionId());
            refreshRunStatus(owner.runId());
        });
    }

    @Override
    public Optional<OutfitRecommendationResult> latest(String externalUserId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return jdbc.query("""
                SELECT id, anchor_wardrobe_item_id, missing_item_json, created_at
                FROM fashion_outfit_recommendation_runs
                WHERE app_user_id = ? ORDER BY created_at DESC, id DESC LIMIT 1
                """, (rs, row) -> new RunRow(rs.getString("id"), rs.getLong("anchor_wardrobe_item_id"),
                rs.getString("missing_item_json"), rs.getTimestamp("created_at").toInstant()),
                scope.appUserId()).stream().findFirst().map(this::hydrate);
    }

    @Override
    public int recoverInterruptedRenders() {
        return transactions.execute(status -> {
            int recovered = jdbc.update("""
                    UPDATE fashion_outfit_recommendation_options
                    SET render_status = 'SUBMITTED', claimed_at = NULL
                    WHERE render_status = 'PROCESSING'
                    """);
            jdbc.update("""
                    UPDATE fashion_outfit_recommendation_runs run_record
                    SET recommendation_status = 'RENDERING'
                    WHERE EXISTS (
                        SELECT 1 FROM fashion_outfit_recommendation_options option_record
                        WHERE option_record.recommendation_run_id = run_record.id
                          AND option_record.render_status IN ('SUBMITTED', 'PROCESSING'))
                    """);
            return recovered;
        });
    }

    private OutfitRecommendationResult hydrate(RunRow run) {
        List<OptionRow> optionRows = jdbc.query("""
                SELECT option_record.id, option_record.option_rank, option_record.total_score,
                    option_record.display_summary, option_record.score_breakdown_json, option_record.evidence_json,
                    option_record.render_status, option_record.failure_summary,
                    output.asset_id AS output_asset_id, output.version AS output_version
                FROM fashion_outfit_recommendation_options option_record
                LEFT JOIN asset_versions output ON output.id = option_record.output_asset_version_id
                WHERE option_record.recommendation_run_id = ?
                ORDER BY option_record.option_rank
                """, (rs, row) -> new OptionRow(rs.getString("id"), rs.getInt("option_rank"),
                rs.getDouble("total_score"), rs.getString("display_summary"),
                rs.getString("score_breakdown_json"), rs.getString("evidence_json"),
                OutfitRenderStatus.valueOf(rs.getString("render_status")), rs.getString("failure_summary"),
                rs.getString("output_asset_id"), rs.getInt("output_version")), run.id());
        Map<String, List<OutfitRecommendationResult.Item>> items = items(optionRows.stream()
                .map(OptionRow::id).toList());
        List<OutfitRecommendationResult.Option> options = optionRows.stream().map(row ->
                new OutfitRecommendationResult.Option(row.id(), row.rank(),
                        items.getOrDefault(row.id(), List.of()), row.totalScore(),
                        read(row.breakdownJson(), new TypeReference<LinkedHashMap<String, Double>>() { },
                                new LinkedHashMap<>()),
                        read(row.evidenceJson(),
                                new TypeReference<List<OutfitRecommendationResult.Evidence>>() { }, List.of()),
                        row.displaySummary(), row.renderStatus(), row.outputAssetId(), row.outputVersion(),
                        row.failureSummary())).toList();
        OutfitRecommendationResult.MissingItem missing = run.missingJson() == null ? null
                : read(run.missingJson(), OutfitRecommendationResult.MissingItem.class, null);
        return new OutfitRecommendationResult(run.id(), run.anchorItemId(), options, missing, run.createdAt());
    }

    private Map<String, List<OutfitRecommendationResult.Item>> items(List<String> optionIds) {
        if (optionIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(optionIds.size(), "?"));
        List<ItemRow> rows = jdbc.query("""
                SELECT item.recommendation_option_id, item.wardrobe_item_id, item.item_role,
                    wardrobe.display_name, wardrobe.category_code, wardrobe.color_primary,
                    asset.id AS asset_version_id, asset.asset_id, asset.version
                FROM fashion_outfit_recommendation_items item
                JOIN fashion_wardrobe_items wardrobe ON wardrobe.id = item.wardrobe_item_id
                JOIN asset_versions asset ON asset.id = item.source_asset_version_id
                WHERE item.recommendation_option_id IN (%s)
                ORDER BY item.recommendation_option_id,
                    CASE item.item_role WHEN 'OUTERWEAR' THEN 1 WHEN 'TOP' THEN 2 ELSE 3 END
                """.formatted(placeholders), (rs, row) -> new ItemRow(
                rs.getString("recommendation_option_id"),
                new OutfitRecommendationResult.Item(rs.getLong("wardrobe_item_id"), rs.getString("item_role"),
                        rs.getString("display_name"), rs.getString("category_code"), rs.getString("color_primary"),
                        rs.getLong("asset_version_id"), rs.getString("asset_id"), rs.getInt("version"))),
                optionIds.toArray());
        Map<String, List<OutfitRecommendationResult.Item>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.optionId(), ignored -> new ArrayList<>()).add(row.item()));
        grouped.replaceAll((ignored, values) -> List.copyOf(values));
        return grouped;
    }

    private void requireOwnedItem(long appUserId, long wardrobeItemId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM fashion_wardrobe_items
                WHERE id = ? AND app_user_id = ? AND item_status = 'ACTIVE'
                """, Integer.class, wardrobeItemId, appUserId);
        if (count == null || count != 1) {
            throw new IllegalArgumentException("Recommendation contains an item not owned by current user");
        }
    }

    private void requireOwnedSource(long appUserId, OutfitRecommendationResult.Item item) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM fashion_wardrobe_items wardrobe
                JOIN app_users user_record ON user_record.id = wardrobe.app_user_id
                JOIN fashion_wardrobe_item_assets link
                    ON link.wardrobe_item_id = wardrobe.id AND link.is_primary = TRUE
                JOIN asset_versions asset ON asset.id = link.asset_version_id
                WHERE wardrobe.id = ? AND wardrobe.app_user_id = ? AND wardrobe.item_status = 'ACTIVE'
                  AND asset.id = ? AND asset.asset_id = ? AND asset.version = ? AND asset.asset_kind = 'IMAGE'
                  AND asset.external_user_id = user_record.external_user_id
                """, Integer.class, item.wardrobeItemId(), appUserId, item.sourceAssetVersionId(),
                item.sourceAssetId(), item.sourceAssetVersion());
        if (count == null || count != 1) {
            throw new IllegalArgumentException("Recommendation item image is not owned by current user");
        }
    }

    private OptionOwner optionOwner(String optionId) {
        return jdbc.query("""
                SELECT option_record.id AS option_id, option_record.recommendation_run_id, run_record.app_user_id
                FROM fashion_outfit_recommendation_options option_record
                JOIN fashion_outfit_recommendation_runs run_record
                    ON run_record.id = option_record.recommendation_run_id
                WHERE option_record.id = ?
                """, (rs, row) -> new OptionOwner(rs.getString("option_id"),
                rs.getString("recommendation_run_id"), rs.getLong("app_user_id")), optionId)
                .stream().findFirst().orElseThrow(() ->
                        new IllegalArgumentException("Outfit recommendation option is not available"));
    }

    private void refreshRunStatus(String runId) {
        RenderCounts counts = jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                    COALESCE(SUM(render_status IN ('SUCCEEDED', 'FALLBACK')), 0) AS completed,
                    COALESCE(SUM(render_status = 'FAILED'), 0) AS failed,
                    COALESCE(SUM(render_status IN ('SUBMITTED', 'PROCESSING')), 0) AS open_count
                FROM fashion_outfit_recommendation_options WHERE recommendation_run_id = ?
                """, (rs, row) -> new RenderCounts(rs.getInt("total"), rs.getInt("completed"),
                rs.getInt("failed"), rs.getInt("open_count")), runId);
        if (counts == null || counts.total() == 0) return;
        String status = counts.open() > 0 ? "RENDERING"
                : counts.completed() == counts.total() ? "COMPLETED"
                : counts.completed() > 0 ? "PARTIAL" : "FAILED";
        jdbc.update("UPDATE fashion_outfit_recommendation_runs SET recommendation_status = ? WHERE id = ?",
                status, runId);
    }

    private Map<String, Object> context(OutfitRecommendationRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("occasionTags", request.occasionTags());
        value.put("seasonTags", request.seasonTags());
        value.put("weatherSummary", request.weatherSummary());
        value.put("styleTags", request.styleTags());
        value.put("targetTime", request.targetTime());
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize outfit recommendation", exception);
        }
    }

    private <T> T read(String value, Class<T> type, T fallback) {
        try {
            return value == null || value.isBlank() ? fallback : objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            return fallback;
        }
    }

    private <T> T read(String value, TypeReference<T> type, T fallback) {
        try {
            return value == null || value.isBlank() ? fallback : objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            return fallback;
        }
    }

    private static String role(String value) {
        String role = text(value, 24).toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("TOP", "BOTTOM", "OUTERWEAR").contains(role)) {
            throw new IllegalArgumentException("Unsupported outfit item role");
        }
        return role;
    }

    private static String identifier(String value) {
        String id = text(value, 36);
        if (id.isBlank()) throw new IllegalArgumentException("Recommendation identifier is required");
        return id;
    }

    private static String text(String value, int limit) {
        String clean = value == null ? "" : value.replace('\u0000', ' ').strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    private static String nullable(String value) {
        String clean = text(value, 36);
        return clean.isBlank() ? null : clean;
    }

    private record RenderRow(
            String externalUserId,
            String recommendationId,
            String optionId,
            int rank,
            String displaySummary,
            OutfitRenderWork.SourceItem source
    ) { }
    private record OptionOwner(String optionId, String runId, long appUserId) { }
    private record RenderCounts(int total, int completed, int failed, int open) { }
    private record RunRow(String id, long anchorItemId, String missingJson, Instant createdAt) { }
    private record OptionRow(
            String id, int rank, double totalScore, String displaySummary, String breakdownJson,
            String evidenceJson, OutfitRenderStatus renderStatus, String failureSummary,
            String outputAssetId, int outputVersion
    ) { }
    private record ItemRow(String optionId, OutfitRecommendationResult.Item item) { }
}
