package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.FashionReferenceGarment;
import com.example.ykdsummer.fashion.domain.FashionReferenceIndexJob;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcFashionReferenceRepository implements FashionReferenceRepository {
    private static final String LOOK_COLUMNS = """
            id, reference_code, display_name, image_asset_id, image_asset_version, image_media_type,
            source_file_name, source_url, source_site, usage_rights, sha256, phash,
            annotation_schema_version, annotation_json, reference_status, created_at, updated_at
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public JdbcFashionReferenceRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<FashionReferenceLook> findByReferenceCode(String referenceCode) {
        return jdbc.query("SELECT " + LOOK_COLUMNS + " FROM fashion_reference_looks WHERE reference_code = ?",
                (rs, row) -> lookWithoutGarments(rs), text(referenceCode, 160)).stream().findFirst().map(this::hydrate);
    }

    @Override
    public Optional<FashionReferenceLook> findBySha256(String sha256) {
        return jdbc.query("SELECT " + LOOK_COLUMNS + " FROM fashion_reference_looks WHERE sha256 = ?",
                (rs, row) -> lookWithoutGarments(rs), hash(sha256)).stream().findFirst().map(this::hydrate);
    }

    @Override
    public Optional<FashionReferenceLook> findById(long id) {
        return jdbc.query("SELECT " + LOOK_COLUMNS + " FROM fashion_reference_looks WHERE id = ?",
                (rs, row) -> lookWithoutGarments(rs), id).stream().findFirst().map(this::hydrate);
    }

    @Override
    public FashionReferenceLook upsert(FashionReferenceLook draft) {
        if (draft == null || draft.garments().isEmpty()) throw new IllegalArgumentException("reference garments are required");
        findBySha256(draft.sha256()).filter(existing -> !existing.referenceCode().equals(draft.referenceCode()))
                .ifPresent(existing -> { throw new IllegalArgumentException("reference image already exists"); });
        return transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO fashion_reference_looks(
                        reference_code, display_name, image_asset_id, image_asset_version, image_media_type,
                        source_file_name, source_url, source_site, usage_rights, sha256, phash,
                        annotation_schema_version, annotation_json, reference_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), image_asset_id = VALUES(image_asset_id),
                        image_asset_version = VALUES(image_asset_version), image_media_type = VALUES(image_media_type),
                        source_file_name = VALUES(source_file_name), source_url = VALUES(source_url),
                        source_site = VALUES(source_site), usage_rights = VALUES(usage_rights), sha256 = VALUES(sha256),
                        phash = VALUES(phash), annotation_schema_version = VALUES(annotation_schema_version),
                        annotation_json = VALUES(annotation_json), reference_status = VALUES(reference_status),
                        updated_at = CURRENT_TIMESTAMP
                    """, text(draft.referenceCode(), 160), text(draft.displayName(), 255),
                    text(draft.imageAssetId(), 64), Math.max(0, draft.imageAssetVersion()),
                    text(defaulted(draft.imageMediaType(), "image/png"), 64), text(draft.sourceFileName(), 255),
                    text(draft.sourceUrl(), 2048), text(draft.sourceSite(), 128), text(draft.usageRights(), 64),
                    hash(draft.sha256()), text(draft.phash(), 64), text(draft.annotationSchemaVersion(), 16),
                    jsonObject(draft.annotationJson()), status(draft.status()));
            long lookId = jdbc.queryForObject("SELECT id FROM fashion_reference_looks WHERE reference_code = ?",
                    Long.class, text(draft.referenceCode(), 160));
            jdbc.update("DELETE FROM fashion_reference_garments WHERE reference_look_id = ?", lookId);
            for (FashionReferenceGarment garment : draft.garments()) insertGarment(lookId, garment);
            jdbc.update("""
                    INSERT INTO fashion_reference_semantic_index_jobs(reference_look_id, operation, status, attempts, next_attempt_at)
                    VALUES (?, 'UPSERT', 'PENDING', 0, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE operation = 'UPSERT', status = 'PENDING', attempts = 0,
                        next_attempt_at = CURRENT_TIMESTAMP, lease_until = NULL, failure_summary = ''
                    """, lookId);
            return findById(lookId).orElseThrow();
        });
    }

    @Override
    public List<FashionReferenceLook> activeLooks(int limit) {
        return jdbc.query("SELECT " + LOOK_COLUMNS + " FROM fashion_reference_looks "
                        + "WHERE reference_status = 'ACTIVE' ORDER BY updated_at DESC, id DESC LIMIT ?",
                (rs, row) -> lookWithoutGarments(rs), Math.max(1, Math.min(limit, 2000))).stream()
                .map(this::hydrate).toList();
    }

    @Override
    public List<FashionReferenceIndexJob> claimPendingIndexJobs(int limit, Duration lease, int maxAttempts) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        Duration effectiveLease = lease == null || lease.isNegative() || lease.isZero() ? Duration.ofMinutes(2) : lease;
        return transactions.execute(status -> {
            jdbc.update("""
                    UPDATE fashion_reference_semantic_index_jobs SET status = 'PENDING', lease_until = NULL
                    WHERE status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_until <= CURRENT_TIMESTAMP
                    """);
            List<FashionReferenceIndexJob> candidates = jdbc.query("""
                    SELECT id, reference_look_id, operation, attempts
                    FROM fashion_reference_semantic_index_jobs
                    WHERE status = 'PENDING' AND attempts < ? AND next_attempt_at <= CURRENT_TIMESTAMP
                    ORDER BY next_attempt_at, id LIMIT ?
                    """, (rs, row) -> new FashionReferenceIndexJob(rs.getLong("id"),
                    rs.getLong("reference_look_id"), rs.getString("operation"), rs.getInt("attempts")),
                    maxAttempts, boundedLimit);
            List<FashionReferenceIndexJob> claimed = new ArrayList<>();
            Timestamp leaseUntil = Timestamp.from(Instant.now().plus(effectiveLease));
            for (FashionReferenceIndexJob candidate : candidates) {
                int updated = jdbc.update("""
                        UPDATE fashion_reference_semantic_index_jobs
                        SET status = 'PROCESSING', attempts = attempts + 1, lease_until = ?
                        WHERE id = ? AND status = 'PENDING' AND attempts = ?
                        """, leaseUntil, candidate.id(), candidate.attempts());
                if (updated == 1) claimed.add(new FashionReferenceIndexJob(candidate.id(), candidate.referenceLookId(),
                        candidate.operation(), candidate.attempts() + 1));
            }
            return List.copyOf(claimed);
        });
    }

    @Override
    public void completeIndexJob(long jobId, String contentHash) {
        jdbc.update("""
                UPDATE fashion_reference_semantic_index_jobs
                SET status = 'SUCCEEDED', lease_until = NULL, content_hash = ?, failure_summary = '' WHERE id = ?
                """, text(contentHash, 64), jobId);
    }

    @Override
    public void retryIndexJob(long jobId, String failureSummary, int maxAttempts, Duration delay) {
        Instant next = Instant.now().plus(delay == null || delay.isNegative() ? Duration.ofSeconds(5) : delay);
        jdbc.update("""
                UPDATE fashion_reference_semantic_index_jobs
                SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = ?, lease_until = NULL, failure_summary = ? WHERE id = ?
                """, maxAttempts, Timestamp.from(next), text(failureSummary, 512), jobId);
    }

    private void insertGarment(long lookId, FashionReferenceGarment garment) {
        jdbc.update("""
                INSERT INTO fashion_reference_garments(
                    reference_look_id, item_index, display_name, category_code, sub_category_code, target_gender,
                    color_primary, color_secondary_json, accent_colors_json, style_tags_json, fit_code, pattern_code,
                    silhouette_code, length_code, material_tags_json, season_tags_json, occasion_tags_json,
                    formality_level, visibility_status, visible_ratio, confidence, attributes_json,
                    cutout_asset_id, cutout_asset_version, cutout_asset_media_type, cutout_status,
                    cutout_source_path, cutout_sha256, cutout_model, cutout_duration_seconds, cutout_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, lookId, Math.max(1, garment.itemIndex()), text(garment.displayName(), 128),
                code(garment.categoryCode(), "UNKNOWN"), code(garment.subCategoryCode(), "UNKNOWN"),
                gender(garment.targetGender()), code(garment.colorPrimary(), ""), jsonArray(garment.secondaryColors()),
                jsonArray(garment.accentColors()), jsonArray(garment.styleTags()), code(garment.fitCode(), ""),
                code(garment.patternCode(), ""), code(garment.silhouetteCode(), ""), code(garment.lengthCode(), ""),
                jsonArray(garment.materialTags()), jsonArray(garment.seasonTags()), jsonArray(garment.occasionTags()),
                Math.max(0, Math.min(5, garment.formalityLevel())), code(garment.visibilityStatus(), "UNKNOWN"),
                decimal(garment.visibleRatio()), decimal(garment.confidence()), jsonObject(garment.attributesJson()),
                text(garment.cutoutAssetId(), 64), Math.max(0, garment.cutoutAssetVersion()),
                text(defaulted(garment.cutoutAssetMediaType(), "image/png"), 64), cutoutStatus(garment.cutoutStatus()),
                text(garment.cutoutSourcePath(), 1024), optionalHash(garment.cutoutSha256()),
                text(garment.cutoutModel(), 128), nonNegative(garment.cutoutDurationSeconds()),
                text(garment.cutoutError(), 512));
    }

    private FashionReferenceLook lookWithoutGarments(ResultSet rs) throws java.sql.SQLException {
        long id = rs.getLong("id");
        return new FashionReferenceLook(id, rs.getString("reference_code"), rs.getString("display_name"),
                rs.getString("image_asset_id"), rs.getInt("image_asset_version"), rs.getString("image_media_type"),
                rs.getString("source_file_name"), rs.getString("source_url"), rs.getString("source_site"),
                rs.getString("usage_rights"), rs.getString("sha256"), rs.getString("phash"),
                rs.getString("annotation_schema_version"), rs.getString("annotation_json"),
                rs.getString("reference_status"), List.of(), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private FashionReferenceLook hydrate(FashionReferenceLook look) {
        return new FashionReferenceLook(look.id(), look.referenceCode(), look.displayName(), look.imageAssetId(),
                look.imageAssetVersion(), look.imageMediaType(), look.sourceFileName(), look.sourceUrl(),
                look.sourceSite(), look.usageRights(), look.sha256(), look.phash(), look.annotationSchemaVersion(),
                look.annotationJson(), look.status(), garments(look.id()), look.createdAt(), look.updatedAt());
    }

    private List<FashionReferenceGarment> garments(long lookId) {
        return jdbc.query("""
                SELECT id, reference_look_id, item_index, display_name, category_code, sub_category_code,
                    target_gender, color_primary, color_secondary_json, accent_colors_json, style_tags_json,
                    fit_code, pattern_code, silhouette_code, length_code, material_tags_json, season_tags_json,
                    occasion_tags_json, formality_level, visibility_status, visible_ratio, confidence, attributes_json,
                    cutout_asset_id, cutout_asset_version, cutout_asset_media_type, cutout_status,
                    cutout_source_path, cutout_sha256, cutout_model, cutout_duration_seconds, cutout_error
                FROM fashion_reference_garments WHERE reference_look_id = ? ORDER BY item_index
                """, (rs, row) -> new FashionReferenceGarment(rs.getLong("id"), rs.getLong("reference_look_id"),
                rs.getInt("item_index"), rs.getString("display_name"), rs.getString("category_code"),
                rs.getString("sub_category_code"), rs.getString("target_gender"), rs.getString("color_primary"),
                stringList(rs.getString("color_secondary_json")), stringList(rs.getString("accent_colors_json")),
                stringList(rs.getString("style_tags_json")), rs.getString("fit_code"), rs.getString("pattern_code"),
                rs.getString("silhouette_code"), rs.getString("length_code"),
                stringList(rs.getString("material_tags_json")), stringList(rs.getString("season_tags_json")),
                stringList(rs.getString("occasion_tags_json")), rs.getInt("formality_level"),
                rs.getString("visibility_status"), rs.getBigDecimal("visible_ratio"), rs.getBigDecimal("confidence"),
                rs.getString("attributes_json"), rs.getString("cutout_asset_id"),
                rs.getInt("cutout_asset_version"), rs.getString("cutout_asset_media_type"),
                rs.getString("cutout_status"), rs.getString("cutout_source_path"), rs.getString("cutout_sha256"),
                rs.getString("cutout_model"), rs.getBigDecimal("cutout_duration_seconds"),
                rs.getString("cutout_error")), lookId);
    }

    private String jsonArray(List<String> values) {
        List<String> cleaned = new ArrayList<>(new LinkedHashSet<>((values == null ? List.<String>of() : values).stream()
                .map(value -> text(value, 128)).filter(value -> !value.isBlank()).toList()));
        try { return objectMapper.writeValueAsString(cleaned); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("invalid reference tag list", failure); }
    }

    private String jsonObject(String value) {
        try {
            JsonNode node = objectMapper.readTree(defaulted(value, "{}"));
            if (node == null || !node.isObject()) throw new IllegalArgumentException("annotation must be a JSON object");
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("annotation must be valid JSON", failure);
        }
    }

    private List<String> stringList(String value) {
        try {
            JsonNode node = objectMapper.readTree(defaulted(value, "[]"));
            if (node == null || !node.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            node.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) result.add(item.asText()); });
            return List.copyOf(result);
        } catch (JsonProcessingException ignored) { return List.of(); }
    }

    private static java.math.BigDecimal decimal(java.math.BigDecimal value) {
        if (value == null) return java.math.BigDecimal.ZERO;
        return value.max(java.math.BigDecimal.ZERO).min(java.math.BigDecimal.ONE);
    }
    private static java.math.BigDecimal nonNegative(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO : value.max(java.math.BigDecimal.ZERO);
    }
    private static String cutoutStatus(String value) {
        String status = code(value, "PENDING");
        return switch (status) { case "READY", "FAILED", "SKIPPED" -> status; default -> "PENDING"; };
    }
    private static String gender(String value) {
        String code = code(value, "UNISEX");
        return switch (code) { case "MENS", "WOMENS", "UNKNOWN" -> code; default -> "UNISEX"; };
    }
    private static String status(String value) {
        String code = code(value, "DRAFT");
        return switch (code) { case "ACTIVE", "NEEDS_REVIEW", "ARCHIVED" -> code; default -> "DRAFT"; };
    }
    private static String code(String value, String fallback) {
        String cleaned = text(value, 64).toUpperCase(java.util.Locale.ROOT).replace(' ', '_').replace('-', '_');
        return cleaned.matches("[A-Z0-9_]*") && !cleaned.isBlank() ? cleaned : fallback;
    }
    private static String hash(String value) {
        String cleaned = text(value, 64).toLowerCase(java.util.Locale.ROOT);
        if (!cleaned.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 is required");
        return cleaned;
    }
    private static String optionalHash(String value) {
        String cleaned = text(value, 64).toLowerCase(java.util.Locale.ROOT);
        if (cleaned.isBlank()) return "";
        if (!cleaned.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid optional sha256");
        return cleaned;
    }
    private static String text(String value, int limit) {
        String cleaned = value == null ? "" : value.replace('\0', ' ').strip();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }
    private static String defaulted(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
