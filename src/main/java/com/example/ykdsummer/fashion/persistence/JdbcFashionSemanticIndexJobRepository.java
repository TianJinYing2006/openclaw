package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.FashionSemanticIndexJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(
        name = {"app.persistence.enabled", "app.fashion.semantic.enabled"},
        havingValue = "true"
)
public class JdbcFashionSemanticIndexJobRepository implements FashionSemanticIndexJobRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcFashionSemanticIndexJobRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void enqueueUpsert(long wardrobeItemId) {
        jdbc.update("""
                INSERT INTO fashion_semantic_index_jobs(wardrobe_item_id, operation, status, attempts, next_attempt_at)
                VALUES (?, 'UPSERT', 'PENDING', 0, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE operation = 'UPSERT', status = 'PENDING', attempts = 0,
                    next_attempt_at = CURRENT_TIMESTAMP, lease_until = NULL, failure_summary = ''
                """, wardrobeItemId);
    }

    @Override
    public List<FashionSemanticIndexJob> claimPending(int limit, Duration lease, int maxAttempts) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        Duration effectiveLease = lease == null || lease.isNegative() || lease.isZero()
                ? Duration.ofMinutes(2) : lease;
        return transactions.execute(status -> {
            jdbc.update("""
                    UPDATE fashion_semantic_index_jobs
                    SET status = 'PENDING', lease_until = NULL
                    WHERE status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_until <= CURRENT_TIMESTAMP
                    """);
            List<FashionSemanticIndexJob> candidates = jdbc.query("""
                    SELECT id, wardrobe_item_id, operation, attempts
                    FROM fashion_semantic_index_jobs
                    WHERE status = 'PENDING' AND attempts < ? AND next_attempt_at <= CURRENT_TIMESTAMP
                    ORDER BY next_attempt_at, id
                    LIMIT ?
                    """, (rs, row) -> new FashionSemanticIndexJob(
                    rs.getLong("id"), rs.getLong("wardrobe_item_id"),
                    rs.getString("operation"), rs.getInt("attempts")), maxAttempts, boundedLimit);
            List<FashionSemanticIndexJob> claimed = new ArrayList<>();
            Timestamp leaseUntil = Timestamp.from(Instant.now().plus(effectiveLease));
            for (FashionSemanticIndexJob candidate : candidates) {
                int updated = jdbc.update("""
                        UPDATE fashion_semantic_index_jobs
                        SET status = 'PROCESSING', attempts = attempts + 1, lease_until = ?
                        WHERE id = ? AND status = 'PENDING' AND attempts = ?
                        """, leaseUntil, candidate.id(), candidate.attempts());
                if (updated == 1) {
                    claimed.add(new FashionSemanticIndexJob(candidate.id(), candidate.wardrobeItemId(),
                            candidate.operation(), candidate.attempts() + 1));
                }
            }
            return List.copyOf(claimed);
        });
    }

    @Override
    public void complete(long jobId, String contentHash) {
        jdbc.update("""
                UPDATE fashion_semantic_index_jobs
                SET status = 'SUCCEEDED', lease_until = NULL, content_hash = ?, failure_summary = ''
                WHERE id = ?
                """, safe(contentHash, 64), jobId);
    }

    @Override
    public void retry(long jobId, String failureSummary, int maxAttempts, Duration delay) {
        Instant nextAttempt = Instant.now().plus(delay == null || delay.isNegative() ? Duration.ofSeconds(5) : delay);
        jdbc.update("""
                UPDATE fashion_semantic_index_jobs
                SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = ?, lease_until = NULL, failure_summary = ?
                WHERE id = ?
                """, maxAttempts, Timestamp.from(nextAttempt), safe(failureSummary, 512), jobId);
    }

    private static String safe(String value, int limit) {
        String cleaned = value == null ? "" : value.replace('\u0000', ' ').strip();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }
}
