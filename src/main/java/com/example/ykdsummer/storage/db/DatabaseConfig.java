package com.example.ykdsummer.storage.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SQLite 数据源配置和建表初始化。
 * <p>创建 HikariCP 连接池连接 SQLite 文件，并在启动时执行建表 DDL。</p>
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean
    DataSource sqliteDataSource(SqliteChatMemoryProperties properties) {
        Path dbPath = properties.getPath().toAbsolutePath().normalize();
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create database directory: " + dbPath.getParent(), e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1); // SQLite only supports one writer at a time
        config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON");
        config.setPoolName("sqlite");
        log.info("SQLite data source configured: {}", dbPath);
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 启动时自动建表 */
    @Bean
    DatabaseInitializer databaseInitializer(JdbcTemplate jdbc) {
        return new DatabaseInitializer(jdbc);
    }

    static class DatabaseInitializer {

        private final JdbcTemplate jdbc;

        DatabaseInitializer(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
            createTables();
        }

        void createTables() {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        user_id      TEXT    NOT NULL,
                        chat_type    TEXT    NOT NULL DEFAULT 'single'
                                             CHECK(chat_type IN ('single', 'group')),
                        nick_name    TEXT    DEFAULT '',
                        status       TEXT    NOT NULL DEFAULT 'active'
                                             CHECK(status IN ('active', 'archived')),
                        msg_count    INTEGER NOT NULL DEFAULT 0,
                        window_size  INTEGER NOT NULL DEFAULT 20,
                        created_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
                        updated_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
                        PRIMARY KEY (user_id, chat_type)
                    )
                    """);

            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id         TEXT    NOT NULL,
                        chat_type       TEXT    NOT NULL DEFAULT 'single',
                        role            TEXT    NOT NULL CHECK(role IN ('user','assistant','system')),
                        content         TEXT    NOT NULL,
                        msg_type        TEXT    DEFAULT 'text',
                        wx_msg_id       TEXT    DEFAULT '',
                        token_count     INTEGER DEFAULT 0,
                        created_at      TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
                        FOREIGN KEY (user_id, chat_type) REFERENCES chat_sessions(user_id, chat_type)
                            ON DELETE CASCADE
                    )
                    """);

            jdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_messages_lookup
                        ON chat_messages(user_id, chat_type, created_at)
                    """);

            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS user_contexts (
                        user_id      TEXT NOT NULL,
                        chat_type    TEXT NOT NULL DEFAULT 'single',
                        context_key  TEXT NOT NULL,
                        context_val  TEXT NOT NULL DEFAULT '',
                        updated_at   TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                        PRIMARY KEY (user_id, chat_type, context_key)
                    )
                    """);

            log.info("SQLite tables initialized");
        }
    }
}
