package com.wechatbot.fashion.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatbot.fashion.admin.config.AdminWebProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL-backed lifecycle for local platform users and their one-to-one bot instances. */
@Service
public class AdminPlatformService {
    private static final String LOCAL_ADMIN = "local-admin";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final ObjectProvider<TransactionTemplate> transactionProvider;
    private final ObjectMapper objectMapper;
    private final AdminWebProperties properties;
    private final Clock clock;

    public AdminPlatformService(
            ObjectProvider<JdbcTemplate> jdbcProvider,
            ObjectProvider<TransactionTemplate> transactionProvider,
            ObjectMapper objectMapper,
            AdminWebProperties properties
    ) {
        this.jdbcProvider = jdbcProvider;
        this.transactionProvider = transactionProvider;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = Clock.system(BUSINESS_ZONE);
    }

    public Dashboard dashboard() {
        return dashboardSnapshot().dashboard();
    }

    /** One MySQL read snapshot for the overview, so cards and instance rows share the same usage window. */
    public DashboardSnapshot dashboardSnapshot() {
        return inTransaction(() -> {
            UsageWindow window = currentUsageWindow();
            return new DashboardSnapshot(dashboard(window), dashboardInstances(window), Instant.now(clock), window.start());
        });
    }

    private Dashboard dashboard(UsageWindow window) {
        JdbcTemplate jdbc = jdbc();
        long userCount = count(jdbc, "SELECT COUNT(*) FROM platform_users WHERE status = 'ACTIVE'");
        long connectedCount = count(jdbc, "SELECT COUNT(*) FROM bot_instances WHERE lifecycle_state <> 'ARCHIVED' AND connection_status = 'CONNECTED'");
        long pendingCount = count(jdbc, "SELECT COUNT(*) FROM bot_instances WHERE lifecycle_state <> 'ARCHIVED' AND connection_status <> 'CONNECTED'");
        UsageTotals usage = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN usage_kind = 'MODEL' THEN quantity ELSE 0 END), 0),
                       COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0),
                       COALESCE(SUM(total_tokens), 0), COALESCE(SUM(CASE WHEN usage_kind IN ('IMAGE_GENERATION', 'IMAGE_EDIT') THEN quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN usage_kind = 'TTS' THEN quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN usage_kind IN ('TOOL', 'TOOL_SUCCESS', 'TOOL_FAILURE') THEN quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN usage_kind = 'TOOL_FAILURE' THEN quantity ELSE 0 END), 0)
                FROM ai_usage_events WHERE created_at >= ? AND created_at < ?
                """, (rs, ignored) -> new UsageTotals(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)),
                Timestamp.from(window.start()), Timestamp.from(window.end()));
        return new Dashboard(userCount, connectedCount, pendingCount,
                usage == null ? UsageTotals.empty() : usage, listUsers());
    }

    /** Today's durable usage grouped by active instance; runtime-only counters are added by the web layer. */
    public List<DashboardInstance> dashboardInstances() {
        return dashboardSnapshot().instances();
    }

    private List<DashboardInstance> dashboardInstances(UsageWindow window) {
        return jdbc().query("""
                SELECT b.id, b.platform_user_id, u.username, b.connection_status, b.last_error, b.updated_at,
                       COALESCE(SUM(CASE WHEN e.usage_kind = 'MODEL' THEN e.quantity ELSE 0 END), 0),
                       COALESCE(SUM(e.total_tokens), 0),
                       COALESCE(SUM(CASE WHEN e.usage_kind IN ('IMAGE_GENERATION', 'IMAGE_EDIT') THEN e.quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.usage_kind = 'TTS' THEN e.quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.usage_kind IN ('TOOL', 'TOOL_SUCCESS', 'TOOL_FAILURE') THEN e.quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.usage_kind = 'TOOL_FAILURE' THEN e.quantity ELSE 0 END), 0)
                FROM bot_instances b
                JOIN platform_users u ON u.id = b.platform_user_id
                LEFT JOIN ai_usage_events e ON e.instance_id = b.id AND e.created_at >= ? AND e.created_at < ?
                WHERE b.lifecycle_state <> 'ARCHIVED' AND u.status = 'ACTIVE'
                GROUP BY b.id, u.id, u.username, b.connection_status, b.last_error, b.updated_at, u.created_at
                ORDER BY u.created_at DESC
                """, (rs, ignored) -> new DashboardInstance(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), instant(rs.getTimestamp(6)), rs.getLong(7), rs.getLong(8), rs.getLong(9),
                rs.getLong(10), rs.getLong(11), rs.getLong(12)), Timestamp.from(window.start()), Timestamp.from(window.end()));
    }

    /** Today's durable model/media/tool totals for one active managed instance. */
    public UsageTotals usageSummary(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return UsageTotals.empty();
        String prefix = managedUserPrefix(instanceId) + "%";
        UsageWindow window = currentUsageWindow();
        UsageTotals usage = jdbc().queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN usage_kind = 'MODEL' THEN quantity ELSE 0 END), 0),
                       COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0),
                       COALESCE(SUM(total_tokens), 0),
                       COALESCE(SUM(CASE WHEN usage_kind IN ('IMAGE_GENERATION', 'IMAGE_EDIT') THEN quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN usage_kind = 'TTS' THEN quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN usage_kind IN ('TOOL', 'TOOL_SUCCESS', 'TOOL_FAILURE') THEN quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN usage_kind = 'TOOL_FAILURE' THEN quantity ELSE 0 END), 0)
                FROM ai_usage_events
                WHERE (instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?))
                  AND created_at >= ? AND created_at < ?
                """, (rs, ignored) -> new UsageTotals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)), instanceId, prefix,
                Timestamp.from(window.start()), Timestamp.from(window.end()));
        return usage == null ? UsageTotals.empty() : usage;
    }

    /** Per-tool operational facts. Failures are exceptional callback failures, not parsed natural-language replies. */
    public List<ToolUsage> toolUsage(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return List.of();
        String prefix = managedUserPrefix(instanceId) + "%";
        UsageWindow window = currentUsageWindow();
        return jdbc().query("""
                SELECT tool_name,
                       COALESCE(SUM(CASE WHEN usage_kind IN ('TOOL', 'TOOL_SUCCESS') THEN quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN usage_kind = 'TOOL_FAILURE' THEN quantity ELSE 0 END), 0),
                       COALESCE(SUM(duration_ms), 0), MAX(created_at)
                FROM ai_usage_events
                WHERE (instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?))
                  AND usage_kind IN ('TOOL', 'TOOL_SUCCESS', 'TOOL_FAILURE')
                  AND tool_name <> '' AND created_at >= ? AND created_at < ?
                GROUP BY tool_name
                ORDER BY MAX(created_at) DESC
                LIMIT 100
                """, (rs, ignored) -> new ToolUsage(rs.getString(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), instant(rs.getTimestamp(5))), instanceId, prefix,
                Timestamp.from(window.start()), Timestamp.from(window.end()));
    }

    /** Real people who have sent messages, deliberately separate from local platform users who own bot instances. */
    public List<ChatUserOverview> chatUsers() {
        return jdbc().query("""
                SELECT a.id, a.external_user_id, a.display_name, a.status, a.created_at, a.last_seen_at,
                       b.id, b.platform_user_id, p.username, b.generation, b.lifecycle_state, b.connection_status,
                       COALESCE(c.conversation_count, 0), COALESCE(c.message_count, 0), COALESCE(t.task_count, 0),
                       COALESCE(s.asset_count, 0), COALESCE(u.model_requests, 0), COALESCE(u.model_failures, 0),
                       COALESCE(u.tool_calls, 0), COALESCE(u.tool_failures, 0), u.last_model_at
                FROM app_users a
                JOIN bot_instances b ON b.id = a.instance_id
                JOIN platform_users p ON p.id = b.platform_user_id
                LEFT JOIN (
                    SELECT c.external_user_id, COUNT(*) AS conversation_count, COUNT(m.id) AS message_count
                    FROM chat_conversations c LEFT JOIN chat_messages m ON m.conversation_id = c.id
                    GROUP BY c.external_user_id
                ) c ON c.external_user_id = a.external_user_id
                LEFT JOIN (
                    SELECT external_user_id, COUNT(*) AS task_count FROM async_tasks GROUP BY external_user_id
                ) t ON t.external_user_id = a.external_user_id
                LEFT JOIN (
                    SELECT external_user_id, COUNT(*) AS asset_count FROM asset_versions GROUP BY external_user_id
                ) s ON s.external_user_id = a.external_user_id
                LEFT JOIN (
                    SELECT external_user_id,
                           SUM(CASE WHEN usage_kind = 'MODEL' THEN quantity ELSE 0 END) AS model_requests,
                           SUM(CASE WHEN usage_kind = 'MODEL_FAILURE' THEN quantity ELSE 0 END) AS model_failures,
                           SUM(CASE WHEN usage_kind = 'TOOL_SUCCESS' THEN quantity ELSE 0 END) AS tool_calls,
                           SUM(CASE WHEN usage_kind = 'TOOL_FAILURE' THEN quantity ELSE 0 END) AS tool_failures,
                           MAX(CASE WHEN usage_kind IN ('MODEL', 'MODEL_FAILURE') THEN created_at ELSE NULL END) AS last_model_at
                    FROM ai_usage_events GROUP BY external_user_id
                ) u ON u.external_user_id = a.external_user_id
                WHERE a.external_user_id LIKE 'managed:%' AND a.instance_id IS NOT NULL
                ORDER BY a.last_seen_at DESC, a.id DESC LIMIT 500
                """, (rs, ignored) -> chatUserOverview(rs));
    }

    public Optional<ChatUserOverview> findChatUser(long appUserId) {
        List<ChatUserOverview> values = jdbc().query("""
                SELECT a.id, a.external_user_id, a.display_name, a.status, a.created_at, a.last_seen_at,
                       b.id, b.platform_user_id, p.username, b.generation, b.lifecycle_state, b.connection_status,
                       COALESCE(c.conversation_count, 0), COALESCE(c.message_count, 0), COALESCE(t.task_count, 0),
                       COALESCE(s.asset_count, 0), COALESCE(u.model_requests, 0), COALESCE(u.model_failures, 0),
                       COALESCE(u.tool_calls, 0), COALESCE(u.tool_failures, 0), u.last_model_at
                FROM app_users a
                JOIN bot_instances b ON b.id = a.instance_id
                JOIN platform_users p ON p.id = b.platform_user_id
                LEFT JOIN (
                    SELECT c.external_user_id, COUNT(*) AS conversation_count, COUNT(m.id) AS message_count
                    FROM chat_conversations c LEFT JOIN chat_messages m ON m.conversation_id = c.id
                    GROUP BY c.external_user_id
                ) c ON c.external_user_id = a.external_user_id
                LEFT JOIN (
                    SELECT external_user_id, COUNT(*) AS task_count FROM async_tasks GROUP BY external_user_id
                ) t ON t.external_user_id = a.external_user_id
                LEFT JOIN (
                    SELECT external_user_id, COUNT(*) AS asset_count FROM asset_versions GROUP BY external_user_id
                ) s ON s.external_user_id = a.external_user_id
                LEFT JOIN (
                    SELECT external_user_id,
                           SUM(CASE WHEN usage_kind = 'MODEL' THEN quantity ELSE 0 END) AS model_requests,
                           SUM(CASE WHEN usage_kind = 'MODEL_FAILURE' THEN quantity ELSE 0 END) AS model_failures,
                           SUM(CASE WHEN usage_kind = 'TOOL_SUCCESS' THEN quantity ELSE 0 END) AS tool_calls,
                           SUM(CASE WHEN usage_kind = 'TOOL_FAILURE' THEN quantity ELSE 0 END) AS tool_failures,
                           MAX(CASE WHEN usage_kind IN ('MODEL', 'MODEL_FAILURE') THEN created_at ELSE NULL END) AS last_model_at
                    FROM ai_usage_events GROUP BY external_user_id
                ) u ON u.external_user_id = a.external_user_id
                WHERE a.id = ? AND a.external_user_id LIKE 'managed:%' AND a.instance_id IS NOT NULL
                """, (rs, ignored) -> chatUserOverview(rs), appUserId);
        return values.stream().findFirst();
    }

    /** Latest calls across all chat users of one bot instance. */
    public List<ModelInvocation> modelInvocationsForInstance(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return List.of();
        String prefix = managedUserPrefix(instanceId) + "%";
        return jdbc().query("""
                SELECT external_user_id, provider, model, usage_kind, total_tokens, duration_ms, reported, failure_reason, created_at
                FROM ai_usage_events
                WHERE (instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?))
                  AND usage_kind IN ('MODEL', 'MODEL_FAILURE')
                ORDER BY id DESC LIMIT 100
                """, (rs, ignored) -> modelInvocation(rs), instanceId, prefix);
    }

    /** Latest model calls for one exact chat identity, including failures that have no saved conversation turn. */
    public List<ModelInvocation> modelInvocationsForChatUser(long appUserId) {
        return jdbc().query("""
                SELECT e.external_user_id, e.provider, e.model, e.usage_kind, e.total_tokens, e.duration_ms,
                       e.reported, e.failure_reason, e.created_at
                FROM ai_usage_events e JOIN app_users a ON a.external_user_id = e.external_user_id
                WHERE a.id = ? AND e.usage_kind IN ('MODEL', 'MODEL_FAILURE')
                ORDER BY e.id DESC LIMIT 100
                """, (rs, ignored) -> modelInvocation(rs), appUserId);
    }

    /** Exact, instance-scoped conversation history for one chat identity. */
    public List<ConversationEntry> conversationEntriesForChatUser(long appUserId) {
        List<ConversationEntry> newest = jdbc().query("""
                SELECT m.role, m.direction, m.message_kind, m.content, m.media_asset_id, m.task_id, m.created_at
                FROM app_users a JOIN chat_conversations c ON c.external_user_id = a.external_user_id
                JOIN chat_messages m ON m.conversation_id = c.id
                WHERE a.id = ? ORDER BY m.id DESC LIMIT 200
                """, (rs, ignored) -> new ConversationEntry(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), instant(rs.getTimestamp(7))), appUserId);
        java.util.Collections.reverse(newest);
        return List.copyOf(newest);
    }

    public List<TaskEntry> tasksForChatUser(long appUserId) {
        return jdbc().query("""
                SELECT t.task_id, t.task_type, t.status, t.retry_prompt, t.result_asset_id, t.result_version,
                       t.failure_summary, t.started_at, t.finished_at
                FROM app_users a JOIN async_tasks t ON t.external_user_id = a.external_user_id
                WHERE a.id = ? ORDER BY t.started_at DESC LIMIT 100
                """, (rs, ignored) -> new TaskEntry(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7), instant(rs.getTimestamp(8)),
                instant(rs.getTimestamp(9))), appUserId);
    }

    public List<AssetEntry> assetsForChatUser(long appUserId) {
        return jdbc().query("""
                SELECT v.asset_id, v.version, v.asset_kind, v.storage_provider, v.object_key, v.source, v.prompt, v.created_at
                FROM app_users a JOIN asset_versions v ON v.external_user_id = a.external_user_id
                WHERE a.id = ? ORDER BY v.created_at DESC LIMIT 100
                """, (rs, ignored) -> new AssetEntry(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8))), appUserId);
    }

    public List<ToolUsage> toolUsageForChatUser(long appUserId) {
        return jdbc().query("""
                SELECT e.tool_name,
                       COALESCE(SUM(CASE WHEN e.usage_kind = 'TOOL_SUCCESS' THEN e.quantity ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.usage_kind = 'TOOL_FAILURE' THEN e.quantity ELSE 0 END), 0),
                       COALESCE(SUM(e.duration_ms), 0), MAX(e.created_at)
                FROM app_users a JOIN ai_usage_events e ON e.external_user_id = a.external_user_id
                WHERE a.id = ? AND e.usage_kind IN ('TOOL_SUCCESS', 'TOOL_FAILURE') AND e.tool_name <> ''
                GROUP BY e.tool_name ORDER BY MAX(e.created_at) DESC LIMIT 100
                """, (rs, ignored) -> new ToolUsage(rs.getString(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), instant(rs.getTimestamp(5))), appUserId);
    }

    public List<UserOverview> listUsers() {
        return jdbc().query("""
                SELECT u.id, u.username, u.remark, u.status, u.created_at, u.updated_at,
                       b.id AS instance_id, b.generation, b.lifecycle_state, b.connection_status,
                       b.ilink_account_id, b.last_error, b.last_connected_at, b.updated_at AS instance_updated_at
                FROM platform_users u
                LEFT JOIN bot_instances b ON b.platform_user_id = u.id AND b.lifecycle_state <> 'ARCHIVED'
                ORDER BY u.created_at DESC
                """, (rs, ignored) -> userOverview(rs));
    }

    public List<ManagedInstance> activeInstances() {
        return jdbc().query("""
                SELECT b.id, b.platform_user_id, u.username, b.generation, b.lifecycle_state, b.connection_status
                FROM bot_instances b JOIN platform_users u ON u.id = b.platform_user_id
                WHERE b.lifecycle_state <> 'ARCHIVED' AND u.status = 'ACTIVE'
                ORDER BY b.created_at ASC
                """, (rs, ignored) -> new ManagedInstance(rs.getString(1), rs.getLong(2), rs.getString(3),
                rs.getInt(4), rs.getString(5), rs.getString(6)));
    }

    public boolean hasPlatformUsers() {
        return count(jdbc(), "SELECT COUNT(*) FROM platform_users") > 0;
    }

    public Optional<UserOverview> findUser(long userId) {
        List<UserOverview> values = jdbc().query("""
                SELECT u.id, u.username, u.remark, u.status, u.created_at, u.updated_at,
                       b.id AS instance_id, b.generation, b.lifecycle_state, b.connection_status,
                       b.ilink_account_id, b.last_error, b.last_connected_at, b.updated_at AS instance_updated_at
                FROM platform_users u
                LEFT JOIN bot_instances b ON b.platform_user_id = u.id AND b.lifecycle_state <> 'ARCHIVED'
                WHERE u.id = ?
                """, (rs, ignored) -> userOverview(rs), userId);
        return values.stream().findFirst();
    }

    public List<ArchivedInstance> archivedInstances(long userId) {
        return jdbc().query("""
                SELECT b.id, b.generation, b.connection_status, b.ilink_account_id, b.last_error,
                       b.last_connected_at, b.archived_at, b.created_at
                FROM bot_instances b WHERE b.platform_user_id = ? AND b.lifecycle_state = 'ARCHIVED'
                ORDER BY b.archived_at DESC, b.generation DESC
                """, (rs, ignored) -> new ArchivedInstance(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getString(5), instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7)),
                instant(rs.getTimestamp(8))), userId);
    }

    /** Audit trail across the user's current and archived instances, newest first. */
    public List<InstanceEvent> eventsForUser(long userId) {
        return jdbc().query("""
                SELECT b.id, b.generation, e.event_type, e.details_json, e.created_at
                FROM bot_instance_events e JOIN bot_instances b ON b.id = e.instance_id
                WHERE b.platform_user_id = ? ORDER BY e.id DESC LIMIT 100
                """, (rs, ignored) -> new InstanceEvent(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), instant(rs.getTimestamp(5))), userId);
    }

    public List<InstanceEvent> events(String instanceId) {
        return jdbc().query("""
                SELECT b.id, b.generation, e.event_type, e.details_json, e.created_at
                FROM bot_instance_events e JOIN bot_instances b ON b.id = e.instance_id
                WHERE e.instance_id = ? ORDER BY e.id DESC LIMIT 100
                """, (rs, ignored) -> new InstanceEvent(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), instant(rs.getTimestamp(5))), instanceId);
    }

    public List<ConversationEntry> conversationEntries(String instanceId) {
        String prefix = managedUserPrefix(instanceId);
        List<ConversationEntry> newest = jdbc().query("""
                SELECT m.role, m.direction, m.message_kind, m.content, m.media_asset_id, m.task_id, m.created_at
                FROM chat_messages m JOIN chat_conversations c ON c.id = m.conversation_id
                WHERE c.instance_id = ? OR (c.instance_id IS NULL AND c.external_user_id LIKE ?)
                ORDER BY m.id DESC LIMIT 200
                """, (rs, ignored) -> new ConversationEntry(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), instant(rs.getTimestamp(7))), instanceId, prefix + "%");
        java.util.Collections.reverse(newest);
        return List.copyOf(newest);
    }

    public List<TaskEntry> tasks(String instanceId) {
        String prefix = managedUserPrefix(instanceId);
        return jdbc().query("""
                SELECT task_id, task_type, status, retry_prompt, result_asset_id, result_version, failure_summary, started_at, finished_at
                FROM async_tasks WHERE instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?)
                ORDER BY started_at DESC LIMIT 100
                """, (rs, ignored) -> new TaskEntry(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7), instant(rs.getTimestamp(8)),
                instant(rs.getTimestamp(9))), instanceId, prefix + "%");
    }

    public List<AssetEntry> assets(String instanceId) {
        String prefix = managedUserPrefix(instanceId);
        return jdbc().query("""
                SELECT asset_id, version, asset_kind, storage_provider, object_key, source, prompt, created_at
                FROM asset_versions WHERE instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?)
                ORDER BY created_at DESC LIMIT 100
                """, (rs, ignored) -> new AssetEntry(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8))),
                instanceId, prefix + "%");
    }

    public UserOverview createUser(String username, String remark) {
        String safeName = validateUsername(username);
        String safeRemark = limit(remark, 255);
        return inTransaction(() -> {
            JdbcTemplate jdbc = jdbc();
            if (count(jdbc, "SELECT COUNT(*) FROM platform_users WHERE status = 'ACTIVE'") >= properties.getMaxInstances()) {
                throw new IllegalStateException("The local instance limit is " + properties.getMaxInstances());
            }
            int inserted = jdbc.update("INSERT INTO platform_users(username, remark) VALUES (?, ?)", safeName, safeRemark);
            if (inserted != 1) throw new IllegalStateException("Could not create platform user");
            Long userId = jdbc.queryForObject("SELECT id FROM platform_users WHERE username = ?", Long.class, safeName);
            if (userId == null) throw new IllegalStateException("Could not load created platform user");
            String instanceId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO bot_instances(id, platform_user_id, generation, lifecycle_state, connection_status) VALUES (?, ?, 1, 'PENDING_QR', 'PENDING_QR')",
                    instanceId, userId);
            instanceEvent(jdbc, instanceId, "INSTANCE_CREATED", Map.of("username", safeName));
            audit(jdbc, "CREATE_USER", "platform_user", String.valueOf(userId), Map.of("username", safeName));
            return findUser(userId).orElseThrow();
        });
    }

    /** Marks an instance as ready to request a QR code. The runtime manager starts the SDK separately. */
    public void requestBinding(long userId) {
        inTransaction(() -> {
            JdbcTemplate jdbc = jdbc();
            String instanceId = activeInstanceId(jdbc, userId);
            jdbc.update("UPDATE bot_instances SET lifecycle_state = 'PENDING_QR', connection_status = 'PENDING_QR', last_error = NULL WHERE id = ?", instanceId);
            instanceEvent(jdbc, instanceId, "QR_REQUESTED", Map.of());
            audit(jdbc, "REQUEST_QR", "bot_instance", instanceId, Map.of("platformUserId", userId));
            return null;
        });
    }

    public void archiveActiveInstance(long userId, String reason) {
        inTransaction(() -> {
            JdbcTemplate jdbc = jdbc();
            String instanceId = activeInstanceId(jdbc, userId);
            jdbc.update("UPDATE bot_instances SET lifecycle_state = 'ARCHIVED', connection_status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP WHERE id = ?", instanceId);
            instanceEvent(jdbc, instanceId, "INSTANCE_ARCHIVED", Map.of("reason", limit(reason, 255)));
            audit(jdbc, "ARCHIVE_INSTANCE", "bot_instance", instanceId, Map.of("reason", limit(reason, 255)));
            return null;
        });
    }

    public void restoreArchivedInstance(long userId, String archivedInstanceId) {
        inTransaction(() -> {
            JdbcTemplate jdbc = jdbc();
            Long owner = jdbc.queryForObject("SELECT platform_user_id FROM bot_instances WHERE id = ? AND lifecycle_state = 'ARCHIVED'", Long.class, archivedInstanceId);
            if (owner == null || owner != userId) throw new IllegalArgumentException("Archived bot instance was not found");
            jdbc.update("UPDATE bot_instances SET lifecycle_state = 'ARCHIVED', connection_status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP WHERE platform_user_id = ? AND lifecycle_state <> 'ARCHIVED'", userId);
            int changed = jdbc.update("UPDATE bot_instances SET lifecycle_state = 'PENDING_QR', connection_status = 'PENDING_QR', archived_at = NULL, last_error = NULL WHERE id = ?", archivedInstanceId);
            if (changed != 1) throw new IllegalStateException("Could not restore archived bot instance");
            instanceEvent(jdbc, archivedInstanceId, "INSTANCE_RESTORED", Map.of());
            audit(jdbc, "RESTORE_INSTANCE", "bot_instance", archivedInstanceId, Map.of("platformUserId", userId));
            return null;
        });
    }

    public void permanentlyDeleteArchivedInstance(long userId, String instanceId, String confirmation, String reason) {
        inTransaction(() -> {
            JdbcTemplate jdbc = jdbc();
            String username = jdbc.queryForObject("SELECT username FROM platform_users WHERE id = ?", String.class, userId);
            if (username == null || !username.equals(confirmation == null ? "" : confirmation.strip())) {
                throw new IllegalArgumentException("Username confirmation does not match");
            }
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM bot_instances WHERE id = ? AND platform_user_id = ? AND lifecycle_state = 'ARCHIVED'", Integer.class, instanceId, userId);
            if (exists == null || exists == 0) throw new IllegalArgumentException("Only archived instances can be permanently deleted");
            String safeReason = limit(reason, 255);
            String prefix = managedUserPrefix(instanceId) + "%";
            audit(jdbc, "PERMANENT_DELETE_INSTANCE", "bot_instance", instanceId, Map.of("reason", safeReason, "username", username));
            jdbc.update("DELETE FROM ai_usage_events WHERE instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?)", instanceId, prefix);
            jdbc.update("DELETE FROM chat_messages WHERE task_id IN (SELECT task_id FROM async_tasks WHERE instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?))", instanceId, prefix);
            jdbc.update("DELETE FROM async_tasks WHERE instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?)", instanceId, prefix);
            jdbc.update("DELETE FROM asset_versions WHERE instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?)", instanceId, prefix);
            jdbc.update("DELETE FROM chat_conversations WHERE instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?)", instanceId, prefix);
            jdbc.update("DELETE FROM app_users WHERE instance_id = ? OR (instance_id IS NULL AND external_user_id LIKE ?)", instanceId, prefix);
            jdbc.update("DELETE FROM bot_instances WHERE id = ?", instanceId);
            Long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM bot_instances WHERE platform_user_id = ?", Long.class, userId);
            if (remaining != null && remaining == 0L) {
                jdbc.update("DELETE FROM platform_users WHERE id = ?", userId);
            }
            return null;
        });
    }

    public void saveSession(String instanceId, SessionCipher.EncryptedValue encrypted, String accountId) {
        jdbc().update("""
                UPDATE bot_instances SET session_ciphertext = ?, session_iv = ?, session_version = 1,
                    ilink_account_id = ?, connection_status = 'CONNECTED', lifecycle_state = 'ACTIVE',
                    last_connected_at = CURRENT_TIMESTAMP, last_error = NULL WHERE id = ?
                """, encrypted.ciphertext(), encrypted.iv(), limit(accountId, 255), instanceId);
        instanceEvent(jdbc(), instanceId, "SESSION_SAVED", Map.of("accountId", limit(accountId, 255)));
    }

    /** Cursor commits use the same encryption path but deliberately do not create one audit event per message batch. */
    public void updateEncryptedSession(String instanceId, SessionCipher.EncryptedValue encrypted, String accountId) {
        jdbc().update("""
                UPDATE bot_instances SET session_ciphertext = ?, session_iv = ?, session_version = 1,
                    ilink_account_id = ?, connection_status = 'CONNECTED', lifecycle_state = 'ACTIVE', last_error = NULL
                WHERE id = ?
                """, encrypted.ciphertext(), encrypted.iv(), limit(accountId, 255), instanceId);
    }

    /** A restored encrypted session is already authenticated in memory, so persist that fact immediately after restart. */
    public void markSessionRestored(String instanceId, String accountId) {
        int changed = jdbc().update("""
                UPDATE bot_instances SET ilink_account_id = ?, connection_status = 'CONNECTED', lifecycle_state = 'ACTIVE',
                    last_connected_at = CURRENT_TIMESTAMP, last_error = NULL
                WHERE id = ? AND lifecycle_state <> 'ARCHIVED'
                """, limit(accountId, 255), instanceId);
        if (changed == 1) {
            instanceEvent(jdbc(), instanceId, "SESSION_RESTORED", Map.of("accountId", limit(accountId, 255)));
        }
    }

    public void clearSession(String instanceId) {
        jdbc().update("""
                UPDATE bot_instances SET session_ciphertext = NULL, session_iv = NULL, ilink_account_id = NULL,
                    lifecycle_state = 'PENDING_QR', connection_status = 'PENDING_QR', last_error = NULL WHERE id = ?
                """, instanceId);
        instanceEvent(jdbc(), instanceId, "SESSION_CLEARED", Map.of());
    }

    /** Imports the existing single-account session only once, before multi-instance mode takes over. */
    public void bootstrapExistingSession(SessionCipher.EncryptedValue encrypted, String accountId) {
        if (hasPlatformUsers()) return;
        UserOverview user = createUser("管理员本人", "从原单账号 iLink 会话迁移");
        if (user.instanceId() == null) throw new IllegalStateException("Could not create initial managed bot instance");
        saveSession(user.instanceId(), encrypted, accountId);
        audit(jdbc(), "BOOTSTRAP_LEGACY_SESSION", "bot_instance", user.instanceId(), Map.of("username", user.username()));
    }

    public Optional<EncryptedSession> encryptedSession(String instanceId) {
        return jdbc().query("SELECT session_ciphertext, session_iv, ilink_account_id FROM bot_instances WHERE id = ?", rs -> {
            if (!rs.next() || rs.getString(1) == null || rs.getString(2) == null) return Optional.empty();
            return Optional.of(new EncryptedSession(rs.getString(1), rs.getString(2), rs.getString(3)));
        }, instanceId);
    }

    public void updateConnection(String instanceId, String connectionStatus, String error) {
        String status = limit(connectionStatus, 32);
        String safeError = limit(error, 512);
        int changed = jdbc().update("""
                UPDATE bot_instances SET connection_status = ?, last_error = ?,
                    last_started_at = CASE WHEN ? = 'STARTING' THEN CURRENT_TIMESTAMP ELSE last_started_at END
                WHERE id = ? AND (connection_status <> ? OR COALESCE(last_error, '') <> ?)
                """, status, safeError, status, instanceId, status, safeError);
        if (changed == 1) {
            instanceEvent(jdbc(), instanceId, "CONNECTION_" + status, Map.of("error", safeError));
        }
    }

    private UserOverview userOverview(ResultSet rs) throws SQLException {
        return new UserOverview(rs.getLong("id"), rs.getString("username"), rs.getString("remark"), rs.getString("status"),
                rs.getString("instance_id"), rs.getInt("generation"), rs.getString("lifecycle_state"), rs.getString("connection_status"),
                rs.getString("ilink_account_id"), rs.getString("last_error"), instant(rs.getTimestamp("last_connected_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("instance_updated_at")));
    }

    private String activeInstanceId(JdbcTemplate jdbc, long userId) {
        List<String> ids = jdbc.query("SELECT id FROM bot_instances WHERE platform_user_id = ? AND lifecycle_state <> 'ARCHIVED' FOR UPDATE", (rs, ignored) -> rs.getString(1), userId);
        if (ids.isEmpty()) throw new IllegalArgumentException("Platform user has no active bot instance");
        return ids.getFirst();
    }

    private void instanceEvent(JdbcTemplate jdbc, String instanceId, String eventType, Map<String, Object> details) {
        jdbc.update("INSERT INTO bot_instance_events(instance_id, event_type, details_json) VALUES (?, ?, CAST(? AS JSON))",
                instanceId, limit(eventType, 64), json(details));
    }

    private void audit(JdbcTemplate jdbc, String action, String targetType, String targetId, Map<String, Object> details) {
        jdbc.update("INSERT INTO admin_audit_logs(actor_user_id, action, target_type, target_id, details_json) VALUES (?, ?, ?, ?, CAST(? AS JSON))",
                LOCAL_ADMIN, action, targetType, targetId, json(details));
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit details", exception);
        }
    }

    private long count(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) throw new IllegalStateException("app.persistence.enabled=true is required for the administrator website");
        return jdbc;
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        TransactionTemplate transaction = transactionProvider.getIfAvailable();
        if (transaction == null) return action.get();
        return transaction.execute(status -> action.get());
    }

    private static String validateUsername(String value) {
        String username = value == null ? "" : value.strip();
        if (username.length() < 1 || username.length() > 64 || !username.matches("[\\p{IsHan}A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Username must be 1-64 Chinese, letter, digit, dot, underscore, or hyphen characters");
        }
        return username;
    }

    private static String limit(String value, int maximum) {
        String safe = value == null ? "" : value.replace('\u0000', ' ').strip();
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static String managedUserPrefix(String instanceId) { return "managed:" + instanceId + ':'; }
    private UsageWindow currentUsageWindow() {
        Instant start = LocalDate.now(clock.withZone(BUSINESS_ZONE)).atStartOfDay(BUSINESS_ZONE).toInstant();
        return new UsageWindow(start, start.plus(java.time.Duration.ofDays(1)));
    }

    private static ChatUserOverview chatUserOverview(ResultSet rs) throws SQLException {
        String externalUserId = rs.getString(2);
        return new ChatUserOverview(rs.getLong(1), externalUserId, maskedChatUser(externalUserId), rs.getString(3),
                rs.getString(4), rs.getString(7), rs.getLong(8), rs.getString(9), rs.getInt(10), rs.getString(11),
                rs.getString(12), rs.getLong(13), rs.getLong(14), rs.getLong(15), rs.getLong(16), rs.getLong(17),
                rs.getLong(18), rs.getLong(19), rs.getLong(20), instant(rs.getTimestamp(21)),
                instant(rs.getTimestamp(6)), instant(rs.getTimestamp(5)));
    }

    private static ModelInvocation modelInvocation(ResultSet rs) throws SQLException {
        String usageKind = rs.getString(4);
        return new ModelInvocation(maskedChatUser(rs.getString(1)), rs.getString(2), rs.getString(3),
                "MODEL".equals(usageKind), rs.getLong(5), rs.getLong(6), rs.getBoolean(7), rs.getString(8),
                instant(rs.getTimestamp(9)));
    }

    private static String maskedChatUser(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) return "未知聊天用户";
        int separator = externalUserId.lastIndexOf(':');
        String raw = separator >= 0 ? externalUserId.substring(separator + 1) : externalUserId;
        int at = raw.indexOf('@');
        String suffix = at >= 0 ? raw.substring(at) : raw.length() <= 4 ? raw : raw.substring(raw.length() - 4);
        String prefix = raw.substring(0, Math.min(6, raw.length()));
        return prefix + "…" + suffix;
    }

    public record Dashboard(long activeUsers, long connectedInstances, long pendingInstances, UsageTotals todayUsage,
                            List<UserOverview> users) { }
    public record DashboardSnapshot(Dashboard dashboard, List<DashboardInstance> instances, Instant capturedAt,
                                    Instant usageWindowStart) { }
    public record UsageTotals(long modelRequests, long promptTokens, long completionTokens, long totalTokens,
                              long imageOperations, long ttsOperations, long toolCalls, long toolFailures) {
        public static UsageTotals empty() { return new UsageTotals(0, 0, 0, 0, 0, 0, 0, 0); }
    }
    public record DashboardInstance(String instanceId, long platformUserId, String username, String persistedStatus, String persistedError,
                                    Instant persistedUpdatedAt, long modelRequestsToday, long totalTokensToday, long imageOperationsToday,
                                    long ttsOperationsToday, long toolCallsToday, long toolFailuresToday) { }
    public record ToolUsage(String toolName, long succeededCalls, long failedCalls, long totalDurationMs,
                            Instant lastCalledAt) {
        public long averageDurationMs() {
            long calls = succeededCalls + failedCalls;
            return calls == 0 ? 0 : totalDurationMs / calls;
        }
    }
    public record UserOverview(long id, String username, String remark, String status, String instanceId, int generation,
                               String lifecycleState, String connectionStatus, String ilinkAccountId, String lastError,
                               Instant lastConnectedAt, Instant createdAt, Instant instanceUpdatedAt) { }
    public record ArchivedInstance(String instanceId, int generation, String connectionStatus, String ilinkAccountId,
                                  String lastError, Instant lastConnectedAt, Instant archivedAt, Instant createdAt) {
        public String instanceShortId() {
            return instanceId == null || instanceId.length() <= 12 ? instanceId : instanceId.substring(0, 8) + "...";
        }
    }
    public record InstanceEvent(String instanceId, int generation, String eventType, String detailsJson,
                                Instant createdAt) {
        public String instanceShortId() {
            return instanceId == null || instanceId.length() <= 12 ? instanceId : instanceId.substring(0, 8) + "...";
        }
    }
    public record EncryptedSession(String ciphertext, String iv, String accountId) { }
    public record ManagedInstance(String instanceId, long platformUserId, String username, int generation,
                                  String lifecycleState, String connectionStatus) { }
    public record ConversationEntry(String role, String direction, String messageKind, String content,
                                    String mediaAssetId, String taskId, Instant createdAt) { }
    public record TaskEntry(String taskId, String taskType, String status, String prompt, String resultAssetId,
                            int resultVersion, String failureSummary, Instant startedAt, Instant finishedAt) { }
    public record AssetEntry(String assetId, int version, String assetKind, String storageProvider, String objectKey,
                             String source, String prompt, Instant createdAt) { }
    public record ChatUserOverview(long id, String externalUserId, String maskedIdentity, String displayName, String status,
                                   String instanceId, long platformUserId, String platformUsername, int instanceGeneration,
                                   String instanceLifecycle, String instanceConnectionStatus, long conversationCount,
                                   long messageCount, long taskCount, long assetCount, long modelRequests, long modelFailures,
                                   long toolCalls, long toolFailures, Instant lastModelAt, Instant lastSeenAt,
                                   Instant createdAt) {
        public boolean archivedInstance() { return "ARCHIVED".equals(instanceLifecycle); }
        public String instanceShortId() {
            return instanceId == null || instanceId.length() <= 12 ? instanceId : instanceId.substring(0, 8) + "...";
        }
        public String lifecycleLabel() { return archivedInstance() ? "历史实例" : "当前实例"; }
        public String lifecycleTone() { return archivedInstance() ? "neutral" : "good"; }
    }
    public record ModelInvocation(String maskedChatUser, String protocol, String model, boolean succeeded, long totalTokens,
                                  long durationMs, boolean reportedUsage, String failureReason, Instant createdAt) { }
    private record UsageWindow(Instant start, Instant end) { }
}
