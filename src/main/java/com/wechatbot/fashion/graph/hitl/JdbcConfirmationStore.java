package com.wechatbot.fashion.graph.hitl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 确认记录存储：优先落 MySQL（V27，唯一键保证幂等）；持久化未启用时退化为进程内内存实现，
 * 保证 HITL 幂等逻辑在任何模式下都可运行（内存态重启丢失，属可接受降级）。
 */
@Component
public class JdbcConfirmationStore implements ConfirmationStore {

    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    /** 内存兜底：confirmationId -> record；runActionKey -> confirmationId */
    private final Map<String, ConfirmationRecord> memoryById = new ConcurrentHashMap<>();
    private final Map<String, String> memoryRunAction = new ConcurrentHashMap<>();

    public JdbcConfirmationStore(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    @Override
    public ConfirmationRecord createIfAbsent(String runId, String userId, String action, Instant expiresAt) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            return memoryCreate(runId, userId, action, expiresAt);
        }
        String confirmationId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_confirmations(confirmation_id, run_id, user_id, action, status, expires_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?)
                ON DUPLICATE KEY UPDATE confirmation_id = confirmation_id
                """, confirmationId, runId, safe(userId), safe(action),
                expiresAt == null ? null : Timestamp.from(expiresAt));
        return findLatest(runId, action).orElseThrow();
    }

    @Override
    public Optional<ConfirmationRecord> find(String confirmationId) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            return Optional.ofNullable(memoryById.get(confirmationId));
        }
        return jdbc.query("SELECT * FROM agent_confirmations WHERE confirmation_id = ?",
                (rs, i) -> map(rs), confirmationId).stream().findFirst();
    }

    @Override
    public Optional<ConfirmationRecord> findLatest(String runId, String action) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            String id = memoryRunAction.get(runActionKey(runId, action));
            return Optional.ofNullable(id == null ? null : memoryById.get(id));
        }
        return jdbc.query("""
                SELECT * FROM agent_confirmations WHERE run_id = ? AND action = ?
                ORDER BY id DESC LIMIT 1
                """, (rs, i) -> map(rs), runId, safe(action)).stream().findFirst();
    }

    @Override
    public boolean confirm(String confirmationId, boolean approved, String resultSummary) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            return memoryConfirm(confirmationId, approved, resultSummary);
        }
        int updated = jdbc.update("""
                UPDATE agent_confirmations
                SET status = ?, result_summary = ?, confirmed_at = CURRENT_TIMESTAMP
                WHERE confirmation_id = ? AND status = 'PENDING'
                """, approved ? ConfirmationRecord.STATUS_CONFIRMED : ConfirmationRecord.STATUS_REJECTED,
                truncate(resultSummary, 1000), confirmationId);
        return updated > 0;
    }

    @Override
    public List<ConfirmationRecord> listPending(int limit) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            List<ConfirmationRecord> pending = new ArrayList<>();
            for (ConfirmationRecord r : memoryById.values()) {
                if (ConfirmationRecord.STATUS_PENDING.equals(r.status())) {
                    pending.add(r);
                }
            }
            pending.sort(Comparator.comparing(ConfirmationRecord::createdAt).reversed());
            return pending.size() > limit ? pending.subList(0, limit) : pending;
        }
        return jdbc.query("""
                SELECT * FROM agent_confirmations WHERE status = 'PENDING'
                ORDER BY created_at DESC LIMIT ?
                """, (rs, i) -> map(rs), limit);
    }

    @Override
    public int expireOverdue(Instant now) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            int count = 0;
            for (Map.Entry<String, ConfirmationRecord> e : memoryById.entrySet()) {
                ConfirmationRecord r = e.getValue();
                if (ConfirmationRecord.STATUS_PENDING.equals(r.status()) && r.isExpired(now)) {
                    e.setValue(new ConfirmationRecord(r.confirmationId(), r.runId(), r.userId(), r.action(),
                            ConfirmationRecord.STATUS_EXPIRED, r.resultSummary(), r.createdAt(), r.confirmedAt(),
                            r.expiresAt()));
                    count++;
                }
            }
            return count;
        }
        return jdbc.update("""
                UPDATE agent_confirmations SET status = 'EXPIRED'
                WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < ?
                """, Timestamp.from(now));
    }

    // ---- 内存兜底实现 ----

    private synchronized ConfirmationRecord memoryCreate(String runId, String userId, String action, Instant expiresAt) {
        String key = runActionKey(runId, action);
        String existingId = memoryRunAction.get(key);
        if (existingId != null && memoryById.containsKey(existingId)) {
            return memoryById.get(existingId);
        }
        ConfirmationRecord record = new ConfirmationRecord(
                UUID.randomUUID().toString(), runId, safe(userId), safe(action),
                ConfirmationRecord.STATUS_PENDING, "", Instant.now(), null, expiresAt);
        memoryById.put(record.confirmationId(), record);
        memoryRunAction.put(key, record.confirmationId());
        return record;
    }

    private synchronized boolean memoryConfirm(String confirmationId, boolean approved, String resultSummary) {
        ConfirmationRecord current = memoryById.get(confirmationId);
        if (current == null || !ConfirmationRecord.STATUS_PENDING.equals(current.status())) {
            return false;
        }
        memoryById.put(confirmationId, new ConfirmationRecord(
                current.confirmationId(), current.runId(), current.userId(), current.action(),
                approved ? ConfirmationRecord.STATUS_CONFIRMED : ConfirmationRecord.STATUS_REJECTED,
                truncate(resultSummary, 1000), current.createdAt(), Instant.now(), current.expiresAt()));
        return true;
    }

    private static ConfirmationRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConfirmationRecord(
                rs.getString("confirmation_id"),
                rs.getString("run_id"),
                rs.getString("user_id"),
                rs.getString("action"),
                rs.getString("status"),
                rs.getString("result_summary"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("confirmed_at")),
                instant(rs.getTimestamp("expires_at")));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String runActionKey(String runId, String action) {
        return safe(runId) + "\u0000" + safe(action);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
