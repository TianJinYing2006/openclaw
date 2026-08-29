package com.wechatbot.fashion.reminder.persistence;

import com.wechatbot.fashion.common.security.TokenCipher;
import com.wechatbot.fashion.persistence.ManagedInstanceScope;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores opaque iLink context tokens without exposing them through logs or admin APIs. */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcILinkReplyContextPersistence implements ILinkReplyContextPersistence {
    private final JdbcTemplate jdbc;
    private final TokenCipher tokenCipher;

    public JdbcILinkReplyContextPersistence(JdbcTemplate jdbc, TokenCipher tokenCipher) {
        this.jdbc = jdbc;
        this.tokenCipher = tokenCipher;
    }

    @Override
    public void save(String externalUserId, String contextToken) {
        if (blank(externalUserId) || blank(contextToken)) return;
        ManagedInstanceScope scope = ManagedInstanceScope.parse(externalUserId);
        Long platformUserId = scope.resolvePlatformUserId(jdbc);
        jdbc.update("""
                INSERT INTO ilink_reply_contexts(external_user_id, platform_user_id, instance_id, context_token, observed_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE platform_user_id = COALESCE(VALUES(platform_user_id), platform_user_id),
                    instance_id = COALESCE(VALUES(instance_id), instance_id), context_token = VALUES(context_token),
                    observed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                """, externalUserId, platformUserId, scope.instanceId(), tokenCipher.encrypt(contextToken));
    }

    @Override
    public Optional<String> find(String externalUserId) {
        if (blank(externalUserId)) return Optional.empty();
        try {
            String stored = jdbc.queryForObject(
                    "SELECT context_token FROM ilink_reply_contexts WHERE external_user_id = ?", String.class, externalUserId);
            return blank(stored) ? Optional.empty() : Optional.of(tokenCipher.decrypt(stored));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
