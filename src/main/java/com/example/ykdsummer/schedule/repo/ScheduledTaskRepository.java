package com.example.ykdsummer.schedule.repo;

import com.example.ykdsummer.schedule.model.ScheduledTask;
import com.example.ykdsummer.schedule.model.TaskStatus;
import com.example.ykdsummer.schedule.model.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 定时任务数据访问层，使用 JdbcTemplate 操作 SQLite scheduled_tasks 表。
 *
 * <p>日期时间在 SQLite 中以 TEXT 格式存储（与 chat_sessions / chat_messages 一致），
 * 格式为 "yyyy-MM-dd HH:mm:ss"。
 */
@Repository
public class ScheduledTaskRepository {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskRepository.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public ScheduledTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ========== 查询 ==========

    /** 按状态查询任务列表 */
    public List<ScheduledTask> findByStatus(TaskStatus status) {
        return jdbc.query("SELECT * FROM scheduled_tasks WHERE status = ? ORDER BY created_at ASC",
                taskRowMapper, status.name());
    }

    /** 查询所有任务 */
    public List<ScheduledTask> findAll() {
        return jdbc.query("SELECT * FROM scheduled_tasks ORDER BY created_at ASC", taskRowMapper);
    }

    /** 按 ID 查询 */
    public Optional<ScheduledTask> findById(Long id) {
        List<ScheduledTask> results = jdbc.query("SELECT * FROM scheduled_tasks WHERE id = ?",
                taskRowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // ========== 写入 ==========

    /** 新增任务，返回自增 ID */
    public long insert(ScheduledTask task) {
        jdbc.update("""
                INSERT INTO scheduled_tasks
                    (name, user_id, task_type, cron_expr, fire_at, status, handler_bean,
                     params, misfire_policy, last_run_at, last_run_result, next_run_at,
                     total_run_count, error_msg)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.getName(),
                task.getUserId(),
                task.getTaskType().name(),
                nullToBlank(task.getCronExpr()),
                localDateTimeToStr(task.getFireAt()),
                task.getStatus().name(),
                task.getHandlerBean(),
                task.getParams() != null ? task.getParams() : "{}",
                task.getMisfirePolicy() != null ? task.getMisfirePolicy() : "IGNORE",
                localDateTimeToStr(task.getLastRunAt()),
                task.getLastRunResult(),
                localDateTimeToStr(task.getNextRunAt()),
                task.getTotalRunCount(),
                task.getErrorMsg()
        );
        // SQLite last_insert_rowid()
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id != null ? id : 0L;
    }

    /** 更新任务状态 */
    public void updateStatus(Long id, TaskStatus status) {
        jdbc.update("UPDATE scheduled_tasks SET status = ?, updated_at = datetime('now','localtime') WHERE id = ?",
                status.name(), id);
    }

    /** 更新状态 + 记录本次执行信息 */
    public void recordRun(Long id, TaskStatus newStatus, String result) {
        jdbc.update("""
                UPDATE scheduled_tasks
                SET status = ?, last_run_at = datetime('now','localtime'),
                    last_run_result = ?, total_run_count = total_run_count + 1,
                    error_msg = CASE WHEN ? != 'SUCCESS' THEN ? ELSE '' END,
                    updated_at = datetime('now','localtime')
                WHERE id = ?
                """, newStatus.name(), result, result, result, id);
    }

    /** 一次性任务：标记完成 */
    public void markFinished(Long id) {
        jdbc.update("""
                UPDATE scheduled_tasks
                SET status = 'FINISHED', last_run_at = datetime('now','localtime'),
                    last_run_result = 'SUCCESS', total_run_count = total_run_count + 1,
                    updated_at = datetime('now','localtime')
                WHERE id = ?
                """, id);
    }

    /** 暂停任务 */
    public void pauseTask(Long id) {
        jdbc.update("UPDATE scheduled_tasks SET status = 'PAUSED', updated_at = datetime('now','localtime') WHERE id = ?",
                id);
    }

    /** 恢复任务为 WAITING */
    public void resumeTask(Long id) {
        jdbc.update("UPDATE scheduled_tasks SET status = 'WAITING', updated_at = datetime('now','localtime') WHERE id = ?",
                id);
    }

    /** 删除任务 */
    public void deleteById(Long id) {
        jdbc.update("DELETE FROM scheduled_tasks WHERE id = ?", id);
    }

    /**
     * 启动时调用：将所有 RUNNING 状态的任务重置为 WAITING。
     * 这些任务在进程异常退出时未正确更新状态，重置后可在下次触发时重新调度。
     */
    public int resetRunningTasks() {
        int affected = jdbc.update("""
                UPDATE scheduled_tasks
                SET status = 'WAITING', error_msg = 'reset: process restarted',
                    updated_at = datetime('now','localtime')
                WHERE status = 'RUNNING'
                """);
        if (affected > 0) {
            log.info("Reset {} RUNNING task(s) to WAITING (previous process was restarted)", affected);
        }
        return affected;
    }

    // ========== RowMapper ==========

    /** 按用户 ID 查询 WAITING 任务 */
    public List<ScheduledTask> findByUserId(String userId) {
        return jdbc.query("SELECT * FROM scheduled_tasks WHERE user_id = ? ORDER BY created_at ASC",
                taskRowMapper, userId);
    }

    private static final RowMapper<ScheduledTask> taskRowMapper = (rs, rowNum) -> {
        ScheduledTask t = new ScheduledTask();
        t.setId(rs.getLong("id"));
        t.setName(rs.getString("name"));
        t.setUserId(rs.getString("user_id"));
        t.setTaskType(TaskType.valueOf(rs.getString("task_type")));
        t.setCronExpr(rs.getString("cron_expr"));
        t.setFireAt(strToLocalDateTime(rs.getString("fire_at")));
        t.setStatus(TaskStatus.valueOf(rs.getString("status")));
        t.setHandlerBean(rs.getString("handler_bean"));
        t.setParams(rs.getString("params"));
        t.setMisfirePolicy(rs.getString("misfire_policy"));
        t.setLastRunAt(strToLocalDateTime(rs.getString("last_run_at")));
        t.setLastRunResult(rs.getString("last_run_result"));
        t.setNextRunAt(strToLocalDateTime(rs.getString("next_run_at")));
        t.setTotalRunCount(rs.getInt("total_run_count"));
        t.setErrorMsg(rs.getString("error_msg"));
        t.setCreatedAt(strToLocalDateTime(rs.getString("created_at")));
        t.setUpdatedAt(strToLocalDateTime(rs.getString("updated_at")));
        return t;
    };

    // ========== 日期工具 ==========

    private static String localDateTimeToStr(LocalDateTime dt) {
        return dt != null ? dt.format(DT_FMT) : null;
    }

    private static LocalDateTime strToLocalDateTime(String str) {
        return str != null && !str.isBlank() ? LocalDateTime.parse(str, DT_FMT) : null;
    }

    private static String nullToBlank(String s) {
        return s != null ? s : "";
    }
}
