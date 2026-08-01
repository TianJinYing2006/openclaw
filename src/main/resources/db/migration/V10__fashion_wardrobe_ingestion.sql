-- Fashion Phase 3A: drafts are intentionally separate from confirmed wardrobe facts.
-- A candidate may be selected and rendered, but only FINAL_CONFIRMED candidates create wardrobe items.
CREATE TABLE IF NOT EXISTS fashion_clothing_candidates (
    id CHAR(36) NOT NULL,
    app_user_id BIGINT NOT NULL,
    instance_id CHAR(36) NULL,
    source_asset_version_id BIGINT NOT NULL,
    candidate_index TINYINT UNSIGNED NOT NULL,
    display_name VARCHAR(128) NOT NULL DEFAULT '',
    category_code VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN',
    color_primary VARCHAR(64) NOT NULL DEFAULT '',
    color_secondary_json JSON NULL,
    style_tags_json JSON NULL,
    fit_code VARCHAR(64) NOT NULL DEFAULT '',
    season_tags_json JSON NULL,
    analysis_attributes_json JSON NULL,
    analysis_confidence DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    quality_score DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    completeness_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    retake_guidance VARCHAR(512) NOT NULL DEFAULT '',
    candidate_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_SELECTION',
    current_cutout_asset_version_id BIGINT NULL,
    confirmed_wardrobe_item_id BIGINT NULL,
    provider VARCHAR(64) NOT NULL DEFAULT '',
    model VARCHAR(128) NOT NULL DEFAULT '',
    prompt_version VARCHAR(64) NOT NULL DEFAULT '',
    selected_at TIMESTAMP NULL,
    final_confirmed_at TIMESTAMP NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_candidate_asset_index (source_asset_version_id, candidate_index),
    KEY idx_fashion_candidates_user_status_expiry (app_user_id, candidate_status, expires_at),
    KEY idx_fashion_candidates_instance_created (instance_id, created_at DESC),
    CONSTRAINT fk_fashion_candidate_user
        FOREIGN KEY (app_user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_candidate_instance
        FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE SET NULL,
    CONSTRAINT fk_fashion_candidate_source_asset
        FOREIGN KEY (source_asset_version_id) REFERENCES asset_versions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_fashion_candidate_cutout_asset
        FOREIGN KEY (current_cutout_asset_version_id) REFERENCES asset_versions(id) ON DELETE SET NULL,
    CONSTRAINT fk_fashion_candidate_wardrobe_item
        FOREIGN KEY (confirmed_wardrobe_item_id) REFERENCES fashion_wardrobe_items(id) ON DELETE SET NULL,
    CONSTRAINT fk_fashion_candidate_category
        FOREIGN KEY (category_code) REFERENCES fashion_taxonomy_nodes(code),
    CONSTRAINT chk_fashion_candidate_confidence
        CHECK (analysis_confidence >= 0 AND analysis_confidence <= 1),
    CONSTRAINT chk_fashion_candidate_quality
        CHECK (quality_score >= 0 AND quality_score <= 1),
    CONSTRAINT chk_fashion_candidate_completeness
        CHECK (completeness_status IN ('READY', 'RETAKE_REQUIRED', 'UNSUPPORTED')),
    CONSTRAINT chk_fashion_candidate_status
        CHECK (candidate_status IN ('PENDING_SELECTION', 'CUTOUT_SUBMITTED', 'AWAITING_FINAL_CONFIRMATION',
            'FINAL_CONFIRMED', 'REJECTED', 'RETAKE_REQUIRED', 'EXPIRED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_garment_cutout_tasks (
    id CHAR(36) NOT NULL,
    candidate_id CHAR(36) NOT NULL,
    app_user_id BIGINT NOT NULL,
    instance_id CHAR(36) NULL,
    source_asset_version_id BIGINT NOT NULL,
    attempt_number INT NOT NULL DEFAULT 1,
    instruction_text VARCHAR(1000) NOT NULL DEFAULT '',
    task_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    output_asset_version_id BIGINT NULL,
    failure_summary VARCHAR(512) NOT NULL DEFAULT '',
    claimed_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_cutout_candidate_attempt (candidate_id, attempt_number),
    KEY idx_fashion_cutout_tasks_due (task_status, expires_at, created_at),
    KEY idx_fashion_cutout_tasks_user_created (app_user_id, created_at DESC),
    CONSTRAINT fk_fashion_cutout_candidate
        FOREIGN KEY (candidate_id) REFERENCES fashion_clothing_candidates(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_cutout_user
        FOREIGN KEY (app_user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_cutout_instance
        FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE SET NULL,
    CONSTRAINT fk_fashion_cutout_source_asset
        FOREIGN KEY (source_asset_version_id) REFERENCES asset_versions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_fashion_cutout_output_asset
        FOREIGN KEY (output_asset_version_id) REFERENCES asset_versions(id) ON DELETE SET NULL,
    CONSTRAINT chk_fashion_cutout_attempt
        CHECK (attempt_number >= 1),
    CONSTRAINT chk_fashion_cutout_status
        CHECK (task_status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
