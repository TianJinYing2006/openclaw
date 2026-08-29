package com.example.ykdsummer.fashion.wardrobe.persistence;

import com.example.ykdsummer.fashion.wardrobe.domain.ClothingAnalysis;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingAnalysisDraft;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionPreferenceUpdate;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionProfileUpdate;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionUserPreference;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionUserProfile;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItem;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItemDraft;
import com.example.ykdsummer.fashion.wardrobe.identity.FashionUserScope;
import com.example.ykdsummer.fashion.wardrobe.identity.FashionUserScopeResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC implementation keeps Fashion records in the same MySQL transaction model as current bot data. */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcFashionCoreRepository implements FashionCoreRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FashionUserScopeResolver scopes;
    private final ObjectMapper objectMapper;

    public JdbcFashionCoreRepository(
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
    public FashionUserScope resolveUser(String externalUserId) {
        return scopes.resolve(externalUserId);
    }

    @Override
    public FashionUserProfile profile(String externalUserId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        ensureProfile(scope.appUserId());
        return loadProfile(scope.appUserId()).orElseThrow();
    }

    @Override
    public FashionUserProfile saveProfile(String externalUserId, FashionProfileUpdate update) {
        if (update == null) throw new IllegalArgumentException("profile update is required");
        validateBudget(update.budgetMin(), update.budgetMax());
        FashionUserScope scope = scopes.resolve(externalUserId);
        ensureProfile(scope.appUserId());
        int completeness = Math.max(0, Math.min(100, update.profileCompleteness()));
        jdbc.update("""
                UPDATE fashion_user_profiles
                SET gender_expression = ?, style_summary = ?, budget_min = ?, budget_max = ?,
                    common_occasions_json = ?, profile_completeness = ?,
                    privacy_consent_at = CASE WHEN ? THEN COALESCE(privacy_consent_at, CURRENT_TIMESTAMP) ELSE privacy_consent_at END
                WHERE app_user_id = ?
                """, text(update.genderExpression(), 32), text(update.styleSummary(), 512), update.budgetMin(), update.budgetMax(),
                jsonArray(update.commonOccasions()), completeness, update.grantPrivacyConsent(), scope.appUserId());
        return loadProfile(scope.appUserId()).orElseThrow();
    }

    @Override
    public FashionUserPreference upsertPreference(String externalUserId, FashionPreferenceUpdate update) {
        if (update == null) throw new IllegalArgumentException("preference update is required");
        FashionUserScope scope = scopes.resolve(externalUserId);
        String dimension = code(update.dimensionCode(), 32, "dimensionCode");
        String value = code(update.valueCode(), 128, "valueCode");
        String polarity = oneOf(update.polarity(), "POSITIVE", "NEGATIVE");
        BigDecimal weight = bounded(update.weight(), BigDecimal.ZERO, new BigDecimal("100"), "weight");
        BigDecimal confidence = bounded(update.confidence(), BigDecimal.ZERO, BigDecimal.ONE, "confidence");
        jdbc.update("""
                INSERT INTO fashion_user_preferences(app_user_id, dimension_code, value_code, polarity, weight, confidence, source, last_evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE weight = VALUES(weight), confidence = VALUES(confidence), source = VALUES(source),
                    last_evidence = VALUES(last_evidence), updated_at = CURRENT_TIMESTAMP
                """, scope.appUserId(), dimension, value, polarity, weight, confidence,
                text(defaulted(update.source(), "USER_DECLARED"), 32), text(update.evidence(), 512));
        return jdbc.queryForObject("""
                SELECT app_user_id, dimension_code, value_code, polarity, weight, confidence, source, last_evidence, created_at, updated_at
                FROM fashion_user_preferences
                WHERE app_user_id = ? AND dimension_code = ? AND value_code = ? AND polarity = ?
                """, (rs, row) -> preference(rs), scope.appUserId(), dimension, value, polarity);
    }

    @Override
    public List<FashionUserPreference> preferences(String externalUserId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return jdbc.query("""
                SELECT app_user_id, dimension_code, value_code, polarity, weight, confidence, source, last_evidence, created_at, updated_at
                FROM fashion_user_preferences WHERE app_user_id = ? ORDER BY dimension_code, value_code, polarity
                """, (rs, row) -> preference(rs), scope.appUserId());
    }

    @Override
    public WardrobeItem createWardrobeItem(String externalUserId, WardrobeItemDraft draft) {
        if (draft == null) throw new IllegalArgumentException("wardrobe item is required");
        FashionUserScope scope = scopes.resolve(externalUserId);
        String category = code(draft.categoryCode(), 64, "categoryCode");
        requireTaxonomy(category);
        BigDecimal confidence = bounded(draft.attributeConfidence(), BigDecimal.ZERO, BigDecimal.ONE, "attributeConfidence");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO fashion_wardrobe_items(
                        app_user_id, instance_id, display_name, parent_category_code, category_code, color_primary,
                        color_secondary_json, style_tags_json, fit_code, pattern_code, season_tags_json,
                        occasion_tags_json, material, attribute_confidence, source, notes,
                        annotation_schema_version, attributes_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, scope.appUserId());
            statement.setString(2, scope.instanceId());
            statement.setString(3, text(draft.displayName(), 128));
            statement.setString(4, text(defaulted(draft.parentCategoryCode(), parentCategory(category)), 64));
            statement.setString(5, category);
            statement.setString(6, text(draft.colorPrimary(), 64));
            statement.setString(7, jsonArray(draft.secondaryColors()));
            statement.setString(8, jsonArray(draft.styleTags()));
            statement.setString(9, text(draft.fitCode(), 64));
            statement.setString(10, text(draft.patternCode(), 64));
            statement.setString(11, jsonArray(draft.seasonTags()));
            statement.setString(12, jsonArray(draft.occasionTags()));
            statement.setString(13, text(draft.material(), 128));
            statement.setBigDecimal(14, confidence);
            statement.setString(15, text(defaulted(draft.source(), "USER_UPLOAD"), 32));
            statement.setString(16, text(draft.notes(), 512));
            statement.setString(17, text(defaulted(draft.annotationSchemaVersion(), "1.0.0"), 16));
            statement.setString(18, jsonObject(draft.attributesJson()));
            return statement;
        }, keyHolder);
        Number generated = keyHolder.getKey();
        if (generated == null) throw new IllegalStateException("Could not create wardrobe item");
        return wardrobeItem(generated.longValue(), scope.appUserId()).orElseThrow();
    }

    @Override
    public void attachWardrobeAsset(String externalUserId, long wardrobeItemId, long assetVersionId,
                                    String assetRole, boolean primary) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        requireOwnedWardrobeItem(scope.appUserId(), wardrobeItemId);
        requireOwnedAsset(scope.externalUserId(), assetVersionId);
        transactions.executeWithoutResult(status -> {
            if (primary) {
                jdbc.update("UPDATE fashion_wardrobe_item_assets SET is_primary = FALSE WHERE wardrobe_item_id = ?", wardrobeItemId);
            }
            jdbc.update("""
                    INSERT INTO fashion_wardrobe_item_assets(wardrobe_item_id, asset_version_id, asset_role, is_primary)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE asset_role = VALUES(asset_role), is_primary = VALUES(is_primary)
                    """, wardrobeItemId, assetVersionId, text(defaulted(assetRole, "PRIMARY"), 32), primary);
        });
    }

    @Override
    public Optional<Long> findOwnedImageAssetVersion(String externalUserId, String assetId, Integer version) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        String asset = text(assetId, 64);
        if (asset.isBlank() || !asset.startsWith("img_")) {
            throw new IllegalArgumentException("imageAssetId must be an image asset id");
        }
        int requestedVersion = version == null ? 0 : version;
        List<Long> values = requestedVersion < 1
                ? jdbc.query("""
                        SELECT id FROM asset_versions
                        WHERE external_user_id = ? AND asset_id = ? AND asset_kind = 'IMAGE'
                        ORDER BY version DESC LIMIT 1
                        """, (rs, row) -> rs.getLong("id"), scope.externalUserId(), asset)
                : jdbc.query("""
                        SELECT id FROM asset_versions
                        WHERE external_user_id = ? AND asset_id = ? AND version = ? AND asset_kind = 'IMAGE'
                        """, (rs, row) -> rs.getLong("id"), scope.externalUserId(), asset, requestedVersion);
        return values.stream().findFirst();
    }

    @Override
    public ClothingAnalysis recordAnalysis(String externalUserId, long assetVersionId, ClothingAnalysisDraft draft) {
        if (draft == null) throw new IllegalArgumentException("clothing analysis is required");
        FashionUserScope scope = scopes.resolve(externalUserId);
        requireOwnedAsset(scope.externalUserId(), assetVersionId);
        if (draft.wardrobeItemId() != null) requireOwnedWardrobeItem(scope.appUserId(), draft.wardrobeItemId());
        String category = code(defaulted(draft.categoryCode(), "UNKNOWN"), 64, "categoryCode");
        requireTaxonomy(category);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO fashion_clothing_analyses(
                        app_user_id, instance_id, asset_version_id, wardrobe_item_id, category_code, attributes_json,
                        confidence, analysis_status, provider, model, prompt_version, analysis_version, failure_summary)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, scope.appUserId());
            statement.setString(2, scope.instanceId());
            statement.setLong(3, assetVersionId);
            if (draft.wardrobeItemId() == null) statement.setNull(4, java.sql.Types.BIGINT); else statement.setLong(4, draft.wardrobeItemId());
            statement.setString(5, category);
            statement.setString(6, jsonObject(draft.attributesJson()));
            statement.setBigDecimal(7, bounded(draft.confidence(), BigDecimal.ZERO, BigDecimal.ONE, "confidence"));
            statement.setString(8, text(defaulted(draft.analysisStatus(), "SUCCEEDED"), 32));
            statement.setString(9, text(draft.provider(), 64));
            statement.setString(10, text(draft.model(), 128));
            statement.setString(11, text(draft.promptVersion(), 64));
            statement.setInt(12, nextAnalysisVersion(assetVersionId));
            statement.setString(13, text(draft.failureSummary(), 512));
            return statement;
        }, keyHolder);
        Number generated = keyHolder.getKey();
        if (generated == null) throw new IllegalStateException("Could not record clothing analysis");
        return clothingAnalysis(generated.longValue()).orElseThrow();
    }

    @Override
    public List<WardrobeItem> activeWardrobeItems(String externalUserId, int limit) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return jdbc.query("""
                SELECT id, app_user_id, instance_id, display_name, parent_category_code, category_code,
                    color_primary, color_secondary_json, style_tags_json,
                    fit_code, pattern_code, season_tags_json, occasion_tags_json, material, item_status, analysis_status,
                    analysis_version, attribute_confidence, source, notes, annotation_schema_version, attributes_json,
                    created_at, updated_at
                FROM fashion_wardrobe_items
                WHERE app_user_id = ? AND item_status = 'ACTIVE'
                ORDER BY updated_at DESC, id DESC LIMIT ?
                """, (rs, row) -> wardrobeItem(rs), scope.appUserId(), Math.max(1, Math.min(limit, 1000)));
    }

    @Override
    public Optional<WardrobeItem> findWardrobeItemById(long wardrobeItemId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, app_user_id, instance_id, display_name, parent_category_code, category_code,
                        color_primary, color_secondary_json, style_tags_json,
                        fit_code, pattern_code, season_tags_json, occasion_tags_json, material, item_status, analysis_status,
                        analysis_version, attribute_confidence, source, notes, annotation_schema_version, attributes_json,
                        created_at, updated_at
                    FROM fashion_wardrobe_items
                    WHERE id = ?
                    """, (rs, row) -> wardrobeItem(rs), wardrobeItemId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<WardrobeItem> findOwnedWardrobeItem(String externalUserId, long wardrobeItemId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return wardrobeItem(wardrobeItemId, scope.appUserId())
                .filter(item -> "ACTIVE".equals(item.itemStatus()));
    }

    @Override
    public boolean archiveWardrobeItem(String externalUserId, long wardrobeItemId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        requireOwnedWardrobeItem(scope.appUserId(), wardrobeItemId);
        return jdbc.update("""
                UPDATE fashion_wardrobe_items
                SET item_status = 'ARCHIVED'
                WHERE id = ? AND app_user_id = ? AND item_status = 'ACTIVE'
                """, wardrobeItemId, scope.appUserId()) > 0;
    }

    @Override
    public List<String> purgeWardrobeItem(String externalUserId, long wardrobeItemId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        requireOwnedWardrobeItem(scope.appUserId(), wardrobeItemId);
        long tryOnRefs = count("SELECT COUNT(*) FROM fashion_virtual_tryon_tasks WHERE wardrobe_item_id = ?", wardrobeItemId);
        long runRefs = count("SELECT COUNT(*) FROM fashion_outfit_recommendation_runs WHERE anchor_wardrobe_item_id = ?",
                wardrobeItemId);
        long itemRefs = count("SELECT COUNT(*) FROM fashion_outfit_recommendation_items WHERE wardrobe_item_id = ?",
                wardrobeItemId);
        if (tryOnRefs + runRefs + itemRefs > 0) {
            throw new IllegalArgumentException("这件衣服存在试穿或搭配推荐记录，不能彻底删除");
        }
        return transactions.execute(status -> {
            List<Long> assetVersionIds = jdbc.queryForList(
                    "SELECT asset_version_id FROM fashion_wardrobe_item_assets WHERE wardrobe_item_id = ?",
                    Long.class, wardrobeItemId);
            jdbc.update("DELETE FROM fashion_wardrobe_items WHERE id = ? AND app_user_id = ?",
                    wardrobeItemId, scope.appUserId());
            List<String> purged = new ArrayList<>();
            for (Long assetVersionId : assetVersionIds) {
                if (assetVersionId == null) continue;
                String assetId = jdbc.query("""
                                SELECT asset_id FROM asset_versions WHERE id = ?
                                """, (rs, row) -> rs.getString("asset_id"), assetVersionId)
                        .stream().findFirst().orElse(null);
                if (assetId == null || assetId.isBlank()) continue;
                try {
                    // RESTRICT 外键自动保护仍被模板/试衣/分析等引用的共享资产
                    jdbc.update("DELETE FROM asset_versions WHERE id = ?", assetVersionId);
                    if (!purged.contains(assetId)) purged.add(assetId);
                } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
                    // 该资产仍被其他记录引用：保留，不清存储对象
                }
            }
            return List.copyOf(purged);
        });
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    @Override
    public Optional<FashionImageAsset> primaryWardrobeImage(String externalUserId, long wardrobeItemId) {
        return Optional.ofNullable(primaryWardrobeImages(externalUserId, List.of(wardrobeItemId))
                .get(wardrobeItemId));
    }

    @Override
    public Map<Long, FashionImageAsset> primaryWardrobeImages(
            String externalUserId, Collection<Long> wardrobeItemIds
    ) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        List<Long> ids = wardrobeItemIds == null ? List.of() : wardrobeItemIds.stream()
                .filter(java.util.Objects::nonNull).filter(id -> id > 0).distinct().limit(1000).toList();
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(scope.appUserId());
        arguments.add(scope.externalUserId());
        arguments.addAll(ids);
        Map<Long, FashionImageAsset> images = new LinkedHashMap<>();
        jdbc.query("""
                SELECT asset.id, asset.asset_id, asset.version, asset.mime_type
                    , item.id AS wardrobe_item_id
                FROM fashion_wardrobe_items item
                JOIN fashion_wardrobe_item_assets link
                    ON link.wardrobe_item_id = item.id AND link.is_primary = TRUE
                JOIN asset_versions asset ON asset.id = link.asset_version_id
                WHERE item.app_user_id = ?
                  AND asset.external_user_id = ?
                  AND item.id IN (%s)
                  AND item.item_status = 'ACTIVE'
                  AND asset.asset_kind = 'IMAGE'
                ORDER BY item.id, link.created_at DESC
                """.formatted(placeholders), rs -> {
            long itemId = rs.getLong("wardrobe_item_id");
            images.putIfAbsent(itemId, new FashionImageAsset(rs.getLong("id"), rs.getString("asset_id"),
                    rs.getInt("version"), rs.getString("mime_type")));
        }, arguments.toArray());
        return java.util.Collections.unmodifiableMap(images);
    }

    private void ensureProfile(long appUserId) {
        jdbc.update("INSERT IGNORE INTO fashion_user_profiles(app_user_id) VALUES (?)", appUserId);
    }

    private Optional<FashionUserProfile> loadProfile(long appUserId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT app_user_id, gender_expression, style_summary, budget_min, budget_max, common_occasions_json,
                        profile_completeness, privacy_consent_at, created_at, updated_at
                    FROM fashion_user_profiles WHERE app_user_id = ?
                    """, (rs, row) -> profile(rs), appUserId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private Optional<WardrobeItem> wardrobeItem(long wardrobeItemId, long appUserId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, app_user_id, instance_id, display_name, parent_category_code, category_code,
                        color_primary, color_secondary_json, style_tags_json,
                        fit_code, pattern_code, season_tags_json, occasion_tags_json, material, item_status, analysis_status,
                        analysis_version, attribute_confidence, source, notes, annotation_schema_version, attributes_json,
                        created_at, updated_at
                    FROM fashion_wardrobe_items WHERE id = ? AND app_user_id = ?
                    """, (rs, row) -> wardrobeItem(rs), wardrobeItemId, appUserId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private Optional<ClothingAnalysis> clothingAnalysis(long analysisId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, app_user_id, instance_id, asset_version_id, wardrobe_item_id, category_code, attributes_json,
                        confidence, analysis_status, provider, model, prompt_version, analysis_version, failure_summary,
                        created_at, updated_at
                    FROM fashion_clothing_analyses WHERE id = ?
                    """, (rs, row) -> clothingAnalysis(rs), analysisId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private void requireTaxonomy(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM fashion_taxonomy_nodes WHERE code = ? AND status = 'ACTIVE'",
                Integer.class, code);
        if (count == null || count != 1) throw new IllegalArgumentException("Unknown Fashion taxonomy code: " + code);
    }

    private void requireOwnedWardrobeItem(long appUserId, long wardrobeItemId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM fashion_wardrobe_items WHERE id = ? AND app_user_id = ?",
                Integer.class, wardrobeItemId, appUserId);
        if (count == null || count != 1) throw new IllegalArgumentException("Wardrobe item is not owned by the current user");
    }

    private void requireOwnedAsset(String externalUserId, long assetVersionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM asset_versions WHERE id = ? AND external_user_id = ?",
                Integer.class, assetVersionId, externalUserId);
        if (count == null || count != 1) throw new IllegalArgumentException("Asset is not owned by the current user");
    }

    private int nextAnalysisVersion(long assetVersionId) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(analysis_version), 0) FROM fashion_clothing_analyses WHERE asset_version_id = ?",
                Integer.class, assetVersionId);
        return (version == null ? 0 : version) + 1;
    }

    private FashionUserProfile profile(ResultSet rs) throws java.sql.SQLException {
        return new FashionUserProfile(rs.getLong("app_user_id"), rs.getString("gender_expression"), rs.getString("style_summary"),
                rs.getBigDecimal("budget_min"), rs.getBigDecimal("budget_max"), stringList(rs.getString("common_occasions_json")),
                rs.getInt("profile_completeness"), instant(rs.getTimestamp("privacy_consent_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private FashionUserPreference preference(ResultSet rs) throws java.sql.SQLException {
        return new FashionUserPreference(rs.getLong("app_user_id"), rs.getString("dimension_code"), rs.getString("value_code"),
                rs.getString("polarity"), rs.getBigDecimal("weight"), rs.getBigDecimal("confidence"), rs.getString("source"),
                rs.getString("last_evidence"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private WardrobeItem wardrobeItem(ResultSet rs) throws java.sql.SQLException {
        return new WardrobeItem(rs.getLong("id"), rs.getLong("app_user_id"), rs.getString("instance_id"),
                rs.getString("display_name"), rs.getString("parent_category_code"), rs.getString("category_code"),
                rs.getString("color_primary"), stringList(rs.getString("color_secondary_json")), stringList(rs.getString("style_tags_json")),
                rs.getString("fit_code"), rs.getString("pattern_code"), stringList(rs.getString("season_tags_json")),
                stringList(rs.getString("occasion_tags_json")), rs.getString("material"), rs.getString("item_status"),
                rs.getString("analysis_status"), rs.getInt("analysis_version"), rs.getBigDecimal("attribute_confidence"),
                rs.getString("source"), rs.getString("notes"), rs.getString("annotation_schema_version"),
                defaulted(rs.getString("attributes_json"), "{}"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private ClothingAnalysis clothingAnalysis(ResultSet rs) throws java.sql.SQLException {
        long wardrobe = rs.getLong("wardrobe_item_id");
        Long wardrobeItemId = rs.wasNull() ? null : wardrobe;
        return new ClothingAnalysis(rs.getLong("id"), rs.getLong("app_user_id"), rs.getString("instance_id"),
                rs.getLong("asset_version_id"), wardrobeItemId, rs.getString("category_code"), rs.getString("attributes_json"),
                rs.getBigDecimal("confidence"), rs.getString("analysis_status"), rs.getString("provider"), rs.getString("model"),
                rs.getString("prompt_version"), rs.getInt("analysis_version"), rs.getString("failure_summary"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private String jsonArray(List<String> values) {
        List<String> source = values == null ? List.of() : values;
        List<String> cleaned = new ArrayList<>(new LinkedHashSet<>(source.stream()
                .map(value -> text(value, 128)).filter(value -> !value.isBlank()).toList()));
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Fashion value list", exception);
        }
    }

    private String jsonObject(String value) {
        String candidate = value == null || value.isBlank() ? "{}" : value.strip();
        try {
            JsonNode node = objectMapper.readTree(candidate);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("attributesJson must be a JSON object");
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("attributesJson must be valid JSON", exception);
        }
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            node.forEach(value -> { if (value.isTextual()) values.add(value.asText()); });
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static BigDecimal bounded(BigDecimal value, BigDecimal min, BigDecimal max, String field) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static void validateBudget(BigDecimal min, BigDecimal max) {
        if (min != null && min.signum() < 0 || max != null && max.signum() < 0) {
            throw new IllegalArgumentException("budget cannot be negative");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("budgetMin cannot exceed budgetMax");
        }
    }

    private static String code(String value, int limit, String field) {
        String candidate = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (candidate.isBlank() || candidate.length() > limit || !candidate.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException(field + " must be an uppercase underscore code");
        }
        return candidate;
    }

    private static String oneOf(String value, String... candidates) {
        String candidate = code(value, 32, "polarity");
        for (String allowed : candidates) if (allowed.equals(candidate)) return candidate;
        throw new IllegalArgumentException("Unsupported polarity");
    }

    private static String parentCategory(String category) {
        return switch (category) {
            case "T_SHIRT", "SHIRT", "KNITWEAR" -> "TOP";
            case "JACKET" -> "OUTERWEAR";
            case "JEANS", "STRAIGHT_PANTS", "SKIRT" -> "BOTTOM";
            case "SNEAKERS", "LOAFERS" -> "SHOES";
            case "DRESS" -> "ONE_PIECE";
            default -> category;
        };
    }

    private static String text(String value, int limit) {
        String candidate = value == null ? "" : value.replace('\u0000', ' ').strip();
        return candidate.length() <= limit ? candidate : candidate.substring(0, limit);
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
