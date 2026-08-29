package com.example.ykdsummer.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Parses the managed runtime namespace without changing the legacy external user identifier. */
public record ManagedInstanceScope(String instanceId) {
    private static final String PREFIX = "managed:";

    public static ManagedInstanceScope parse(String externalUserId) {
        String value = externalUserId == null ? "" : externalUserId;
        int idStart = PREFIX.length();
        int idEnd = idStart + 36;
        if (!value.startsWith(PREFIX) || value.length() <= idEnd || value.charAt(idEnd) != ':') {
            return new ManagedInstanceScope(null);
        }
        try {
            return new ManagedInstanceScope(UUID.fromString(value.substring(idStart, idEnd)).toString());
        } catch (IllegalArgumentException ignored) {
            return new ManagedInstanceScope(null);
        }
    }

    public boolean managed() {
        return instanceId != null;
    }

    public Long resolvePlatformUserId(JdbcTemplate jdbc) {
        if (!managed() || jdbc == null) return null;
        return jdbc.query("SELECT platform_user_id FROM bot_instances WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, instanceId);
    }
}
