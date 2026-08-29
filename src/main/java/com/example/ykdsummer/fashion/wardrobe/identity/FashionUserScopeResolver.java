package com.example.ykdsummer.fashion.wardrobe.identity;

import com.example.ykdsummer.persistence.ManagedInstanceScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Resolves the current scoped chat identity to the existing app_users primary key. */
@Component
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class FashionUserScopeResolver {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public FashionUserScopeResolver(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public FashionUserScope resolve(String externalUserId) {
        String external = required(externalUserId, "externalUserId");
        return transactions.execute(status -> {
            ManagedInstanceScope managed = ManagedInstanceScope.parse(external);
            Long platformUserId = managed.resolvePlatformUserId(jdbc);
            jdbc.update("""
                    INSERT INTO app_users(external_user_id, platform_user_id, instance_id)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE last_seen_at = CURRENT_TIMESTAMP,
                        platform_user_id = COALESCE(VALUES(platform_user_id), platform_user_id),
                        instance_id = COALESCE(VALUES(instance_id), instance_id)
                    """, external, platformUserId, managed.instanceId());
            Long appUserId = jdbc.queryForObject(
                    "SELECT id FROM app_users WHERE external_user_id = ?", Long.class, external);
            if (appUserId == null) {
                throw new IllegalStateException("Could not resolve app user");
            }
            return new FashionUserScope(appUserId, external, managed.instanceId(), platformUserId);
        });
    }

    private static String required(String value, String field) {
        String cleaned = value == null ? "" : value.replace('\u0000', ' ').strip();
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return cleaned;
    }
}
