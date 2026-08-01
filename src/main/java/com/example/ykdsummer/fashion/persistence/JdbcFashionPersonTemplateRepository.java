package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.FashionPersonTemplate;
import com.example.ykdsummer.fashion.domain.FashionPersonTemplateStatus;
import com.example.ykdsummer.fashion.domain.PersonTemplateAssessment;
import com.example.ykdsummer.fashion.identity.FashionUserScope;
import com.example.ykdsummer.fashion.identity.FashionUserScopeResolver;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC implementation keeps each person's try-on template within their current managed WeChat scope. */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcFashionPersonTemplateRepository implements FashionPersonTemplateRepository {
    private static final String COLUMNS = """
            t.id, t.app_user_id, t.instance_id, t.source_asset_version_id, a.asset_id, a.version, t.display_name,
            t.template_status, t.suitability_summary, t.retake_guidance, t.analysis_confidence, t.is_active,
            t.created_at, t.updated_at
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FashionUserScopeResolver scopes;

    public JdbcFashionPersonTemplateRepository(JdbcTemplate jdbc, TransactionTemplate transactions,
                                               FashionUserScopeResolver scopes) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.scopes = scopes;
    }

    @Override
    public FashionPersonTemplate saveActive(String externalUserId, FashionImageAsset source, String displayName,
                                            PersonTemplateAssessment assessment) {
        if (source == null || assessment == null || !assessment.ready()) {
            throw new IllegalArgumentException("Only a ready person template can be saved");
        }
        FashionUserScope scope = scopes.resolve(externalUserId);
        return transactions.execute(status -> {
            requireOwnedImage(scope, source.assetVersionId());
            FashionPersonTemplate existing = findBySource(scope, source.assetVersionId()).orElse(null);
            String id = existing == null ? UUID.randomUUID().toString() : existing.id();
            jdbc.update("UPDATE fashion_person_templates SET is_active = FALSE WHERE app_user_id = ?", scope.appUserId());
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO fashion_person_templates(id, app_user_id, instance_id, source_asset_version_id,
                            display_name, template_status, suitability_summary, retake_guidance, analysis_confidence, is_active)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                        """, id, scope.appUserId(), scope.instanceId(), source.assetVersionId(), text(displayName, 128),
                        assessment.status().name(), text(assessment.suitabilitySummary(), 512),
                        text(assessment.retakeGuidance(), 512), confidence(assessment.confidence()));
            } else {
                jdbc.update("""
                        UPDATE fashion_person_templates
                        SET instance_id = ?, display_name = ?, template_status = ?, suitability_summary = ?,
                            retake_guidance = ?, analysis_confidence = ?, is_active = TRUE
                        WHERE id = ? AND app_user_id = ?
                        """, scope.instanceId(), text(displayName, 128), assessment.status().name(),
                        text(assessment.suitabilitySummary(), 512), text(assessment.retakeGuidance(), 512),
                        confidence(assessment.confidence()), id, scope.appUserId());
            }
            return require(scope, id);
        });
    }

    @Override
    public List<FashionPersonTemplate> list(String externalUserId, int limit) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return jdbc.query("SELECT " + COLUMNS + " FROM fashion_person_templates t JOIN asset_versions a ON a.id = t.source_asset_version_id "
                        + "WHERE t.app_user_id = ? ORDER BY t.is_active DESC, t.updated_at DESC LIMIT ?",
                (rs, row) -> template(rs), scope.appUserId(), boundedLimit(limit));
    }

    @Override
    public Optional<FashionPersonTemplate> active(String externalUserId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return jdbc.query("SELECT " + COLUMNS + " FROM fashion_person_templates t JOIN asset_versions a ON a.id = t.source_asset_version_id "
                        + "WHERE t.app_user_id = ? AND t.is_active = TRUE ORDER BY t.updated_at DESC LIMIT 1",
                (rs, row) -> template(rs), scope.appUserId()).stream().findFirst();
    }

    @Override
    public FashionPersonTemplate activate(String externalUserId, String templateId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        String id = identifier(templateId);
        return transactions.execute(status -> {
            FashionPersonTemplate target = require(scope, id);
            if (target.status() != FashionPersonTemplateStatus.READY) {
                throw new IllegalStateException("The selected person template needs a clearer photo");
            }
            jdbc.update("UPDATE fashion_person_templates SET is_active = FALSE WHERE app_user_id = ?", scope.appUserId());
            jdbc.update("UPDATE fashion_person_templates SET is_active = TRUE WHERE id = ? AND app_user_id = ?",
                    id, scope.appUserId());
            return require(scope, id);
        });
    }

    private FashionPersonTemplate require(FashionUserScope scope, String id) {
        try {
            return jdbc.queryForObject("SELECT " + COLUMNS + " FROM fashion_person_templates t JOIN asset_versions a ON a.id = t.source_asset_version_id "
                            + "WHERE t.id = ? AND t.app_user_id = ?", (rs, row) -> template(rs), id, scope.appUserId());
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("Person template is not available to the current user");
        }
    }

    private Optional<FashionPersonTemplate> findBySource(FashionUserScope scope, long assetVersionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM fashion_person_templates t JOIN asset_versions a ON a.id = t.source_asset_version_id "
                        + "WHERE t.app_user_id = ? AND t.source_asset_version_id = ?",
                (rs, row) -> template(rs), scope.appUserId(), assetVersionId).stream().findFirst();
    }

    private void requireOwnedImage(FashionUserScope scope, long assetVersionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM asset_versions WHERE id = ? AND external_user_id = ? AND asset_kind = 'IMAGE'",
                Integer.class, assetVersionId, scope.externalUserId());
        if (count == null || count == 0) throw new IllegalArgumentException("Template image is not available to the current user");
    }

    private static FashionPersonTemplate template(ResultSet rs) throws java.sql.SQLException {
        return new FashionPersonTemplate(rs.getString("id"), rs.getLong("app_user_id"), rs.getString("instance_id"),
                rs.getLong("source_asset_version_id"), rs.getString("asset_id"), rs.getInt("version"),
                rs.getString("display_name"), FashionPersonTemplateStatus.valueOf(rs.getString("template_status")),
                rs.getString("suitability_summary"), rs.getString("retake_guidance"), rs.getBigDecimal("analysis_confidence"),
                rs.getBoolean("is_active"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private static Instant instant(Timestamp value) { return value == null ? Instant.EPOCH : value.toInstant(); }
    private static int boundedLimit(int value) { return Math.max(1, Math.min(value, 20)); }
    private static BigDecimal confidence(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0
                ? BigDecimal.ZERO : value;
    }
    private static String text(String value, int limit) {
        String clean = value == null ? "" : value.replace('\u0000', ' ').strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
    private static String identifier(String value) {
        String clean = text(value, 36);
        if (clean.isBlank()) throw new IllegalArgumentException("templateId is required");
        return clean;
    }
}
