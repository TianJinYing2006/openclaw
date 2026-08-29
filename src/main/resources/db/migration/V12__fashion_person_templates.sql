-- A private source image selected by a user for later virtual try-on. The table intentionally stores
-- template suitability only; it does not store inferred face identity, age, body measurements, or biometrics.
CREATE TABLE IF NOT EXISTS fashion_person_templates (
    id CHAR(36) NOT NULL,
    app_user_id BIGINT NOT NULL,
    instance_id CHAR(36) NULL,
    source_asset_version_id BIGINT NOT NULL,
    display_name VARCHAR(128) NOT NULL DEFAULT '',
    template_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    suitability_summary VARCHAR(512) NOT NULL DEFAULT '',
    retake_guidance VARCHAR(512) NOT NULL DEFAULT '',
    analysis_confidence DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_person_template_source (app_user_id, source_asset_version_id),
    KEY idx_fashion_person_templates_user_active (app_user_id, is_active, updated_at DESC),
    CONSTRAINT fk_fashion_person_template_user
        FOREIGN KEY (app_user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_person_template_instance
        FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE SET NULL,
    CONSTRAINT fk_fashion_person_template_asset
        FOREIGN KEY (source_asset_version_id) REFERENCES asset_versions(id) ON DELETE RESTRICT,
    CONSTRAINT chk_fashion_person_template_status
        CHECK (template_status IN ('READY', 'RETAKE_REQUIRED')),
    CONSTRAINT chk_fashion_person_template_confidence
        CHECK (analysis_confidence >= 0 AND analysis_confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
