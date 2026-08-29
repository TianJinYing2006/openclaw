package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.persistence.ManagedInstanceScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Append-only usage facts for the administrator dashboard. Failures are intentionally ignored. */
@Component
public class JdbcUsageEventRecorder implements UsageEventRecorder {
    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public JdbcUsageEventRecorder(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    @Override
    public void recordModel(String userId, String protocol, String model, AiModelUsage usage, long estimatedTotalTokens) {
        recordModel(userId, protocol, model, usage, estimatedTotalTokens, 0);
    }

    @Override
    public void recordModel(String userId, String protocol, String model, AiModelUsage usage,
                            long estimatedTotalTokens, long durationMs) {
        AiModelUsage safe = usage == null ? AiModelUsage.unknown() : usage;
        insert(userId, "MODEL", protocol, model, "", safe.reported() ? safe.promptTokens() : 0,
                safe.reported() ? safe.completionTokens() : 0,
                safe.reported() ? safe.totalTokens() : Math.max(0, estimatedTotalTokens), 1, durationMs, "", safe.reported());
    }

    @Override
    public void recordModelFailure(String userId, String protocol, String model, String failureReason, long durationMs) {
        insert(userId, "MODEL_FAILURE", protocol, model, "", 0, 0, 0, 1, durationMs,
                failureReason, false);
    }

    @Override
    public void recordOperation(String userId, String kind, String model, String toolName, long quantity, long durationMs) {
        insert(userId, kind, "", model, toolName, 0, 0, 0, Math.max(1, quantity), Math.max(0, durationMs), "", false);
    }

    @Override
    public void recordTool(String userId, String toolName, boolean succeeded, long durationMs) {
        insert(userId, succeeded ? "TOOL_SUCCESS" : "TOOL_FAILURE", "spring-ai-tool", "", toolName,
                0, 0, 0, 1, Math.max(0, durationMs), "", false);
    }

    private void insert(String userId, String kind, String provider, String model, String toolName,
                        long promptTokens, long completionTokens, long totalTokens, long quantity,
                        long durationMs, String failureReason, boolean reported) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) return;
        try {
            ManagedInstanceScope scope = ManagedInstanceScope.parse(userId);
            Long platformUserId = scope.resolvePlatformUserId(jdbc);
            if (userId != null && !userId.isBlank() && !"unknown".equals(userId) && !"anonymous".equals(userId)) {
                jdbc.update("""
                        INSERT INTO app_users(external_user_id, platform_user_id, instance_id) VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE last_seen_at = CURRENT_TIMESTAMP,
                            platform_user_id = COALESCE(VALUES(platform_user_id), platform_user_id),
                            instance_id = COALESCE(VALUES(instance_id), instance_id)
                        """, safe(userId), platformUserId, scope.instanceId());
            }
            jdbc.update("""
                    INSERT INTO ai_usage_events(platform_user_id, instance_id, external_user_id, usage_kind, provider, model,
                        tool_name, prompt_tokens, completion_tokens, total_tokens, quantity, duration_ms, failure_reason, reported)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, platformUserId, scope.instanceId(), safe(userId), safe(kind), safe(provider), safe(model), safe(toolName),
                    Math.max(0, promptTokens), Math.max(0, completionTokens), Math.max(0, totalTokens), Math.max(1, quantity),
                    Math.max(0, durationMs), safe(failureReason), reported);
        } catch (RuntimeException ignored) {
            // Metrics must never make a bot response fail.
        }
    }

    private static String safe(String value) { return value == null ? "" : value.strip(); }
}
