package com.example.ykdsummer.persistence;

import com.example.ykdsummer.ai.service.ImageTaskStatusStore.ImageTask;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Operation;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Status;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcImageTaskPersistence implements ImageTaskPersistence {
    private static final Logger log = LoggerFactory.getLogger(JdbcImageTaskPersistence.class);
    private final JdbcTemplate jdbc;

    public JdbcImageTaskPersistence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String userId, ImageTask task) {
        if (userId == null || userId.isBlank() || task == null) return;
        jdbc.update("""
                INSERT INTO async_tasks(task_id, external_user_id, task_type, status, started_at, finished_at,
                    retry_prompt, source_asset_id, source_version, result_asset_id, result_version, failure_summary)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status = VALUES(status), finished_at = VALUES(finished_at),
                    retry_prompt = VALUES(retry_prompt), source_asset_id = VALUES(source_asset_id),
                    source_version = VALUES(source_version), result_asset_id = VALUES(result_asset_id),
                    result_version = VALUES(result_version), failure_summary = VALUES(failure_summary),
                    updated_at = CURRENT_TIMESTAMP
                """, task.taskId(), userId, task.operation().name(), task.status().name(), timestamp(task.startedAt()),
                timestamp(task.finishedAt()), task.retryPrompt(), task.sourceAssetId(), task.sourceVersion(),
                task.resultAssetId(), task.resultVersion(), task.failureSummary());
        jdbc.update("INSERT INTO task_events(task_id, event_type, details) VALUES (?, ?, ?)", task.taskId(),
                task.status().name(), task.failureSummary());
    }

    @Override
    public Optional<ImageTask> find(String userId, String taskId) {
        if (blank(userId) || blank(taskId)) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT task_id, task_type, status, started_at, finished_at, retry_prompt, source_asset_id,
                        source_version, result_asset_id, result_version, failure_summary
                    FROM async_tasks WHERE external_user_id = ? AND task_id = ?
                    """, (rs, row) -> task(rs.getString("task_id"), rs.getString("task_type"), rs.getString("status"),
                    instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                    rs.getString("retry_prompt"), rs.getString("source_asset_id"), rs.getInt("source_version"),
                    rs.getString("result_asset_id"), rs.getInt("result_version"), rs.getString("failure_summary")), userId, taskId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public List<ImageTask> recent(String userId, int limit) {
        if (blank(userId) || limit < 1) return List.of();
        return jdbc.query("""
                SELECT task_id, task_type, status, started_at, finished_at, retry_prompt, source_asset_id,
                    source_version, result_asset_id, result_version, failure_summary
                FROM async_tasks WHERE external_user_id = ? ORDER BY started_at DESC LIMIT ?
                """, (rs, row) -> task(rs.getString("task_id"), rs.getString("task_type"), rs.getString("status"),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                rs.getString("retry_prompt"), rs.getString("source_asset_id"), rs.getInt("source_version"),
                rs.getString("result_asset_id"), rs.getInt("result_version"), rs.getString("failure_summary")), userId, Math.min(limit, 100));
    }

    @Override
    public void clear(String userId) {
        if (blank(userId)) return;
        jdbc.update("""
                DELETE e FROM task_events e JOIN async_tasks t ON t.task_id = e.task_id
                WHERE t.external_user_id = ?
                """, userId);
        jdbc.update("DELETE FROM async_tasks WHERE external_user_id = ?", userId);
    }

    @Override
    public void markInterruptedTasksFailed() {
        int count = jdbc.update("""
                UPDATE async_tasks
                SET status = 'FAILED', finished_at = CURRENT_TIMESTAMP,
                    failure_summary = '服务重启，后台任务未完成，请重新提交', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING'
                """);
        if (count > 0) log.warn("Marked {} interrupted image task(s) as failed after restart", count);
    }

    private static ImageTask task(String taskId, String operation, String status, Instant startedAt, Instant finishedAt,
                                  String retryPrompt, String sourceAssetId, int sourceVersion, String resultAssetId,
                                  int resultVersion, String failureSummary) {
        return new ImageTask(taskId, Operation.valueOf(operation), Status.valueOf(status), startedAt, finishedAt,
                safe(retryPrompt), safe(sourceAssetId), sourceVersion, safe(resultAssetId), resultVersion,
                safe(failureSummary));
    }

    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safe(String value) { return value == null ? "" : value; }
}
