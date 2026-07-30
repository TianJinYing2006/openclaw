package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.FashionTryOnTask;
import com.example.ykdsummer.fashion.domain.FashionTryOnTaskStatus;
import com.example.ykdsummer.fashion.domain.FashionTryOnWork;
import com.example.ykdsummer.fashion.identity.FashionUserScope;
import com.example.ykdsummer.fashion.identity.FashionUserScopeResolver;
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

/** JDBC implementation pins the active template and primary wardrobe image before work enters the queue. */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcFashionTryOnRepository implements FashionTryOnRepository {
    private static final String TASK_COLUMNS = """
            id AS task_id, app_user_id AS task_app_user_id, instance_id AS task_instance_id,
            person_template_id, wardrobe_item_id, person_asset_version_id, garment_asset_version_id,
            task_status, attempt_count, output_asset_version_id, failure_summary, claimed_at, completed_at,
            created_at AS task_created_at, updated_at AS task_updated_at
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FashionUserScopeResolver scopes;

    public JdbcFashionTryOnRepository(JdbcTemplate jdbc, TransactionTemplate transactions, FashionUserScopeResolver scopes) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.scopes = scopes;
    }

    @Override
    public FashionTryOnTask submit(String externalUserId, long wardrobeItemId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return transactions.execute(status -> {
            TemplateSource template = activeTemplate(scope);
            GarmentSource garment = primaryGarment(scope, wardrobeItemId);
            Optional<FashionTryOnTask> existing = openTask(scope.appUserId(), template.templateId(), garment.wardrobeItemId());
            if (existing.isPresent()) return existing.orElseThrow();
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO fashion_virtual_tryon_tasks(
                        id, app_user_id, instance_id, person_template_id, wardrobe_item_id,
                        person_asset_version_id, garment_asset_version_id, task_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED')
                    """, id, scope.appUserId(), scope.instanceId(), template.templateId(), garment.wardrobeItemId(),
                    template.image().assetVersionId(), garment.image().assetVersionId());
            return require(id);
        });
    }

    @Override
    public List<String> pendingTaskIds(int limit) {
        return jdbc.query("""
                SELECT id FROM fashion_virtual_tryon_tasks
                WHERE task_status = 'SUBMITTED'
                ORDER BY created_at, id LIMIT ?
                """, (rs, row) -> rs.getString("id"), boundedLimit(limit));
    }

    @Override
    public Optional<FashionTryOnWork> claim(String taskId, Instant now) {
        String id = identifier(taskId);
        Instant time = now == null ? Instant.now() : now;
        int changed = jdbc.update("""
                UPDATE fashion_virtual_tryon_tasks
                SET task_status = 'PROCESSING', claimed_at = ?, attempt_count = attempt_count + 1
                WHERE id = ? AND task_status = 'SUBMITTED'
                """, Timestamp.from(time), id);
        if (changed == 0) return Optional.empty();
        return jdbc.query("""
                SELECT t.id AS task_id, t.app_user_id AS task_app_user_id, t.instance_id AS task_instance_id,
                    t.person_template_id, t.wardrobe_item_id, t.person_asset_version_id, t.garment_asset_version_id,
                    t.task_status, t.attempt_count, t.output_asset_version_id, t.failure_summary,
                    t.claimed_at, t.completed_at, t.created_at AS task_created_at, t.updated_at AS task_updated_at,
                    u.external_user_id,
                    person.id AS person_id, person.asset_id AS person_asset_id, person.version AS person_version,
                    person.mime_type AS person_mime_type,
                    garment.id AS garment_id, garment.asset_id AS garment_asset_id, garment.version AS garment_version,
                    garment.mime_type AS garment_mime_type,
                    item.category_code
                FROM fashion_virtual_tryon_tasks t
                JOIN app_users u ON u.id = t.app_user_id
                JOIN asset_versions person ON person.id = t.person_asset_version_id
                JOIN asset_versions garment ON garment.id = t.garment_asset_version_id
                JOIN fashion_wardrobe_items item ON item.id = t.wardrobe_item_id
                WHERE t.id = ?
                """, (rs, row) -> work(rs), id).stream().findFirst();
    }

    @Override
    public void succeed(String taskId, String outputAssetId, int outputVersion, Instant completedAt) {
        Instant time = completedAt == null ? Instant.now() : completedAt;
        transactions.executeWithoutResult(status -> {
            FashionTryOnTask task = require(identifier(taskId));
            if (task.status() != FashionTryOnTaskStatus.PROCESSING) return;
            Long outputAssetVersionId = jdbc.queryForObject("""
                    SELECT asset.id
                    FROM asset_versions asset
                    JOIN app_users user_record ON user_record.external_user_id = asset.external_user_id
                    WHERE user_record.id = ? AND asset.asset_id = ? AND asset.version = ? AND asset.asset_kind = 'IMAGE'
                    """, Long.class, task.appUserId(), text(outputAssetId, 64), Math.max(1, outputVersion));
            if (outputAssetVersionId == null) throw new IllegalStateException("Try-on output asset is not owned by task user");
            jdbc.update("""
                    UPDATE fashion_virtual_tryon_tasks
                    SET task_status = 'SUCCEEDED', output_asset_version_id = ?, completed_at = ?, failure_summary = ''
                    WHERE id = ? AND task_status = 'PROCESSING'
                    """, outputAssetVersionId, Timestamp.from(time), task.id());
        });
    }

    @Override
    public void fail(String taskId, String failureSummary, Instant completedAt) {
        Instant time = completedAt == null ? Instant.now() : completedAt;
        jdbc.update("""
                UPDATE fashion_virtual_tryon_tasks
                SET task_status = 'FAILED', failure_summary = ?, completed_at = ?
                WHERE id = ? AND task_status = 'PROCESSING'
                """, text(failureSummary, 512), Timestamp.from(time), identifier(taskId));
    }

    @Override
    public Optional<FashionTryOnTask> latest(String externalUserId) {
        FashionUserScope scope = scopes.resolve(externalUserId);
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM fashion_virtual_tryon_tasks "
                        + "WHERE app_user_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                (rs, row) -> task(rs), scope.appUserId()).stream().findFirst();
    }

    @Override
    public int recoverInterruptedTasks() {
        return jdbc.update("""
                UPDATE fashion_virtual_tryon_tasks
                SET task_status = 'SUBMITTED', claimed_at = NULL
                WHERE task_status = 'PROCESSING'
                """);
    }

    private TemplateSource activeTemplate(FashionUserScope scope) {
        return jdbc.query("""
                SELECT t.id AS template_id, a.id, a.asset_id, a.version, a.mime_type
                FROM fashion_person_templates t
                JOIN asset_versions a ON a.id = t.source_asset_version_id
                WHERE t.app_user_id = ? AND t.is_active = TRUE AND t.template_status = 'READY'
                ORDER BY t.updated_at DESC LIMIT 1
                """, (rs, row) -> new TemplateSource(rs.getString("template_id"), image(rs, "")), scope.appUserId())
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("No active person template is available"));
    }

    private GarmentSource primaryGarment(FashionUserScope scope, long wardrobeItemId) {
        return jdbc.query("""
                SELECT item.id AS wardrobe_item_id, a.id, a.asset_id, a.version, a.mime_type
                FROM fashion_wardrobe_items item
                JOIN fashion_wardrobe_item_assets link ON link.wardrobe_item_id = item.id AND link.is_primary = TRUE
                JOIN asset_versions a ON a.id = link.asset_version_id
                WHERE item.app_user_id = ? AND item.id = ? AND item.item_status = 'ACTIVE' AND a.asset_kind = 'IMAGE'
                ORDER BY link.created_at DESC LIMIT 1
                """, (rs, row) -> new GarmentSource(rs.getLong("wardrobe_item_id"), image(rs, "")),
                scope.appUserId(), wardrobeItemId).stream().findFirst().orElseThrow(() ->
                new IllegalArgumentException("The selected wardrobe item has no usable primary image"));
    }

    private Optional<FashionTryOnTask> openTask(long appUserId, String templateId, long wardrobeItemId) {
        return jdbc.query("SELECT " + TASK_COLUMNS + " FROM fashion_virtual_tryon_tasks "
                        + "WHERE app_user_id = ? AND person_template_id = ? AND wardrobe_item_id = ? "
                        + "AND task_status IN ('SUBMITTED', 'PROCESSING') ORDER BY created_at DESC LIMIT 1",
                (rs, row) -> task(rs), appUserId, templateId, wardrobeItemId).stream().findFirst();
    }

    private FashionTryOnTask require(String taskId) {
        try {
            return jdbc.queryForObject("SELECT " + TASK_COLUMNS + " FROM fashion_virtual_tryon_tasks WHERE id = ?",
                    (rs, row) -> task(rs), taskId);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("Virtual try-on task is not available");
        }
    }

    private static FashionTryOnWork work(ResultSet rs) throws java.sql.SQLException {
        return new FashionTryOnWork(rs.getString("external_user_id"), task(rs),
                image(rs, "person_"), image(rs, "garment_"), rs.getString("category_code"));
    }

    private static FashionTryOnTask task(ResultSet rs) throws java.sql.SQLException {
        return new FashionTryOnTask(rs.getString("task_id"), rs.getLong("task_app_user_id"), rs.getString("task_instance_id"),
                rs.getString("person_template_id"), rs.getLong("wardrobe_item_id"), rs.getLong("person_asset_version_id"),
                rs.getLong("garment_asset_version_id"), FashionTryOnTaskStatus.valueOf(rs.getString("task_status")),
                rs.getInt("attempt_count"), longOrNull(rs.getObject("output_asset_version_id")), rs.getString("failure_summary"),
                instant(rs.getTimestamp("claimed_at")), instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("task_created_at")), instant(rs.getTimestamp("task_updated_at")));
    }

    private static FashionImageAsset image(ResultSet rs, String prefix) throws java.sql.SQLException {
        return new FashionImageAsset(rs.getLong(prefix + "id"), rs.getString(prefix + "asset_id"),
                rs.getInt(prefix + "version"), rs.getString(prefix + "mime_type"));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Long longOrNull(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static int boundedLimit(int value) { return Math.max(1, Math.min(value, 16)); }
    private static String identifier(String value) {
        String id = text(value, 36);
        if (id.isBlank()) throw new IllegalArgumentException("taskId is required");
        return id;
    }
    private static String text(String value, int limit) {
        String clean = value == null ? "" : value.replace('\u0000', ' ').strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    private record TemplateSource(String templateId, FashionImageAsset image) { }
    private record GarmentSource(long wardrobeItemId, FashionImageAsset image) { }
}
