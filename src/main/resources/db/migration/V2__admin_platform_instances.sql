CREATE TABLE IF NOT EXISTS platform_users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    remark VARCHAR(255) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS bot_instances (
    id CHAR(36) NOT NULL,
    platform_user_id BIGINT NOT NULL,
    generation INT NOT NULL DEFAULT 1,
    lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'PENDING_QR',
    connection_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_QR',
    ilink_account_id VARCHAR(255) NULL,
    session_ciphertext MEDIUMTEXT NULL,
    session_iv VARCHAR(64) NULL,
    session_version INT NOT NULL DEFAULT 1,
    last_error VARCHAR(512) NULL,
    last_started_at TIMESTAMP NULL,
    last_connected_at TIMESTAMP NULL,
    archived_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    active_binding TINYINT GENERATED ALWAYS AS (CASE WHEN lifecycle_state <> 'ARCHIVED' THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bot_instances_active_user (platform_user_id, active_binding),
    KEY idx_bot_instances_state (lifecycle_state, updated_at DESC),
    CONSTRAINT fk_bot_instances_platform_user FOREIGN KEY (platform_user_id) REFERENCES platform_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS bot_instance_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    instance_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    details_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_bot_instance_events_instance_created (instance_id, created_at DESC),
    CONSTRAINT fk_bot_instance_events_instance FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ai_usage_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    platform_user_id BIGINT NULL,
    instance_id CHAR(36) NULL,
    external_user_id VARCHAR(255) NOT NULL DEFAULT '',
    usage_kind VARCHAR(32) NOT NULL,
    provider VARCHAR(64) NOT NULL DEFAULT '',
    model VARCHAR(128) NOT NULL DEFAULT '',
    tool_name VARCHAR(128) NOT NULL DEFAULT '',
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    quantity BIGINT NOT NULL DEFAULT 1,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    reported BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_usage_events_user_created (platform_user_id, created_at DESC),
    KEY idx_ai_usage_events_instance_created (instance_id, created_at DESC),
    CONSTRAINT fk_ai_usage_events_platform_user FOREIGN KEY (platform_user_id) REFERENCES platform_users(id) ON DELETE SET NULL,
    CONSTRAINT fk_ai_usage_events_instance FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE chat_conversations
    ADD COLUMN platform_user_id BIGINT NULL,
    ADD COLUMN instance_id CHAR(36) NULL,
    ADD KEY idx_chat_conversations_instance_updated (instance_id, updated_at DESC);

ALTER TABLE chat_messages
    ADD COLUMN direction VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
    ADD COLUMN message_kind VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    ADD COLUMN external_message_id VARCHAR(128) NULL,
    ADD COLUMN task_id VARCHAR(64) NULL,
    ADD COLUMN media_asset_id VARCHAR(64) NULL,
    ADD COLUMN provider_metadata JSON NULL,
    ADD KEY idx_chat_messages_external_message (external_message_id),
    ADD KEY idx_chat_messages_task_id (task_id);

ALTER TABLE async_tasks
    ADD COLUMN platform_user_id BIGINT NULL,
    ADD COLUMN instance_id CHAR(36) NULL,
    ADD KEY idx_async_tasks_instance_started (instance_id, started_at DESC);

ALTER TABLE asset_versions
    ADD COLUMN platform_user_id BIGINT NULL,
    ADD COLUMN instance_id CHAR(36) NULL,
    ADD KEY idx_asset_versions_instance_created (instance_id, created_at DESC);
