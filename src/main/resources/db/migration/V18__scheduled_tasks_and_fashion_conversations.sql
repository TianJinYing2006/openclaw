-- 定时任务表：原 SQLite 侧由 DatabaseConfig 启动时自动创建，现迁移至 MySQL。
-- ScheduledTaskRepository 的 INSERT/UPDATE/RowMapper 均基于这些列。
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NULL,
    task_type VARCHAR(16) NOT NULL,
    cron_expr VARCHAR(128) NULL,
    fire_at DATETIME NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    handler_bean VARCHAR(255) NOT NULL,
    params TEXT NULL,
    misfire_policy VARCHAR(32) NOT NULL DEFAULT 'IGNORE',
    last_run_at DATETIME NULL,
    last_run_result TEXT NULL,
    next_run_at DATETIME NULL,
    total_run_count INT NOT NULL DEFAULT 0,
    error_msg TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_scheduled_tasks_status (status),
    KEY idx_scheduled_tasks_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 穿搭对话表：用户画像数据源，原 SQLite 侧由 DatabaseConfig 启动时自动创建。
-- FashionConversationService 的写入/查询均基于这些列。
CREATE TABLE IF NOT EXISTS fashion_conversations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(255) NOT NULL,
    user_input TEXT NOT NULL,
    scene VARCHAR(64) NOT NULL DEFAULT '',
    season VARCHAR(64) NOT NULL DEFAULT '',
    formality INT NOT NULL DEFAULT 0,
    recommendation TEXT NULL,
    user_feedback TEXT NULL,
    embedding TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fashion_conv_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
