package com.example.ykdsummer.storage.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * SQLite 数据库定期清理服务。
 * <p>每日凌晨执行，删除超过 cleanupMaxAge 天未活跃的会话及其消息。</p>
 */
@Service
public class DbCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DbCleanupService.class);

    private final JdbcTemplate jdbc;
    private final SqliteChatMemoryProperties properties;

    public DbCleanupService(JdbcTemplate jdbc, SqliteChatMemoryProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @PostConstruct
    void logSchedule() {
        log.info("DB cleanup scheduled: maxAge={}, intervalMs={}",
                properties.getCleanupMaxAge(), properties.getCleanupIntervalMs());
    }

    /** 每天凌晨 3:00 执行清理 */
    @Scheduled(cron = "0 0 3 * * ?")
    void cleanup() {
        int maxAgeDays = (int) properties.getCleanupMaxAge().toDays();
        if (maxAgeDays <= 0) {
            return;
        }

        // 删除过期的消息（级联由 FK 保证，但先删消息更高效）
        int msgDeleted = jdbc.update("""
                DELETE FROM chat_messages
                WHERE (user_id, chat_type) IN (
                    SELECT user_id, chat_type FROM chat_sessions
                    WHERE updated_at < datetime('now', '-' || ? || ' days', 'localtime')
                )
                """, maxAgeDays);

        // 删除过期的会话
        int sessionDeleted = jdbc.update("""
                DELETE FROM chat_sessions
                WHERE updated_at < datetime('now', '-' || ? || ' days', 'localtime')
                """, maxAgeDays);

        // 执行 VACUUM 回收空间（清理后执行一次）
        if (sessionDeleted > 0 || msgDeleted > 0) {
            jdbc.execute("PRAGMA optimize");
        }

        if (sessionDeleted > 0 || msgDeleted > 0) {
            log.info("DB cleanup removed {} session(s) and {} message(s)", sessionDeleted, msgDeleted);
        }
    }
}
