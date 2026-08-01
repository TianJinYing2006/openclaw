-- Durable recommendation snapshots. Public Looks provide evidence, while every selected item remains user-owned.
CREATE TABLE IF NOT EXISTS fashion_outfit_recommendation_runs (
    id CHAR(36) NOT NULL,
    app_user_id BIGINT NOT NULL,
    instance_id CHAR(36) NULL,
    anchor_wardrobe_item_id BIGINT NOT NULL,
    request_context_json JSON NOT NULL,
    requested_limit TINYINT UNSIGNED NOT NULL DEFAULT 3,
    recommendation_status VARCHAR(24) NOT NULL DEFAULT 'READY',
    missing_item_json JSON NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_fashion_outfit_run_user_created (app_user_id, created_at DESC),
    CONSTRAINT fk_fashion_outfit_run_user
        FOREIGN KEY (app_user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_outfit_run_instance
        FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE SET NULL,
    CONSTRAINT fk_fashion_outfit_run_anchor
        FOREIGN KEY (anchor_wardrobe_item_id) REFERENCES fashion_wardrobe_items(id) ON DELETE RESTRICT,
    CONSTRAINT chk_fashion_outfit_run_limit CHECK (requested_limit BETWEEN 1 AND 3),
    CONSTRAINT chk_fashion_outfit_run_status
        CHECK (recommendation_status IN ('READY', 'RENDERING', 'COMPLETED', 'PARTIAL', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_outfit_recommendation_options (
    id CHAR(36) NOT NULL,
    recommendation_run_id CHAR(36) NOT NULL,
    option_rank TINYINT UNSIGNED NOT NULL,
    total_score DECIMAL(7,4) NOT NULL,
    display_summary VARCHAR(512) NOT NULL,
    score_breakdown_json JSON NOT NULL,
    evidence_json JSON NOT NULL,
    render_status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
    render_attempt_count INT NOT NULL DEFAULT 0,
    output_asset_version_id BIGINT NULL,
    failure_summary VARCHAR(512) NOT NULL DEFAULT '',
    claimed_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_outfit_option_rank (recommendation_run_id, option_rank),
    KEY idx_fashion_outfit_option_dispatch (render_status, created_at),
    CONSTRAINT fk_fashion_outfit_option_run
        FOREIGN KEY (recommendation_run_id) REFERENCES fashion_outfit_recommendation_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_outfit_option_output
        FOREIGN KEY (output_asset_version_id) REFERENCES asset_versions(id) ON DELETE SET NULL,
    CONSTRAINT chk_fashion_outfit_option_rank CHECK (option_rank BETWEEN 1 AND 3),
    CONSTRAINT chk_fashion_outfit_option_score CHECK (total_score BETWEEN 0 AND 100),
    CONSTRAINT chk_fashion_outfit_option_render_status
        CHECK (render_status IN ('SUBMITTED', 'PROCESSING', 'SUCCEEDED', 'FALLBACK', 'FAILED')),
    CONSTRAINT chk_fashion_outfit_option_attempts CHECK (render_attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_outfit_recommendation_items (
    recommendation_option_id CHAR(36) NOT NULL,
    wardrobe_item_id BIGINT NOT NULL,
    item_role VARCHAR(24) NOT NULL,
    source_asset_version_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (recommendation_option_id, item_role),
    KEY idx_fashion_outfit_item_wardrobe (wardrobe_item_id, created_at DESC),
    CONSTRAINT fk_fashion_outfit_item_option
        FOREIGN KEY (recommendation_option_id) REFERENCES fashion_outfit_recommendation_options(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_outfit_item_wardrobe
        FOREIGN KEY (wardrobe_item_id) REFERENCES fashion_wardrobe_items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_fashion_outfit_item_asset
        FOREIGN KEY (source_asset_version_id) REFERENCES asset_versions(id) ON DELETE RESTRICT,
    CONSTRAINT chk_fashion_outfit_item_role
        CHECK (item_role IN ('TOP', 'BOTTOM', 'OUTERWEAR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
