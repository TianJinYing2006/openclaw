CREATE TABLE IF NOT EXISTS ilink_reply_contexts (
    external_user_id VARCHAR(255) NOT NULL,
    platform_user_id BIGINT NULL,
    instance_id CHAR(36) NULL,
    context_token MEDIUMTEXT NOT NULL,
    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (external_user_id),
    KEY idx_ilink_reply_contexts_instance_observed (instance_id, observed_at DESC),
    CONSTRAINT fk_ilink_reply_contexts_platform_user FOREIGN KEY (platform_user_id) REFERENCES platform_users(id) ON DELETE SET NULL,
    CONSTRAINT fk_ilink_reply_contexts_instance FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS reminders (
    id CHAR(36) NOT NULL,
    external_user_id VARCHAR(255) NOT NULL,
    platform_user_id BIGINT NULL,
    instance_id CHAR(36) NULL,
    reminder_text VARCHAR(1000) NOT NULL,
    schedule_type VARCHAR(16) NOT NULL,
    zone_id VARCHAR(64) NOT NULL,
    local_time TIME NULL,
    weekday TINYINT NULL,
    next_fire_at TIMESTAMP NULL,
    last_fire_at TIMESTAMP NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_reminders_due (status, next_fire_at),
    KEY idx_reminders_user_created (external_user_id, created_at DESC),
    KEY idx_reminders_instance_created (instance_id, created_at DESC),
    CONSTRAINT fk_reminders_platform_user FOREIGN KEY (platform_user_id) REFERENCES platform_users(id) ON DELETE SET NULL,
    CONSTRAINT fk_reminders_instance FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS reminder_deliveries (
    id CHAR(36) NOT NULL,
    reminder_id CHAR(36) NOT NULL,
    external_user_id VARCHAR(255) NOT NULL,
    scheduled_for TIMESTAMP NOT NULL,
    message_snapshot VARCHAR(1000) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NULL,
    claimed_at TIMESTAMP NULL,
    sent_at TIMESTAMP NULL,
    failure_summary VARCHAR(512) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reminder_deliveries_occurrence (reminder_id, scheduled_for),
    KEY idx_reminder_deliveries_due (status, next_attempt_at),
    KEY idx_reminder_deliveries_user_created (external_user_id, created_at DESC),
    CONSTRAINT fk_reminder_deliveries_reminder FOREIGN KEY (reminder_id) REFERENCES reminders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
