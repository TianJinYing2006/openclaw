-- A durable two-reference rendering task. Inputs are version-pinned so later wardrobe/template changes
-- cannot alter a task that has already been accepted for the current user.
CREATE TABLE IF NOT EXISTS fashion_virtual_tryon_tasks (
    id CHAR(36) NOT NULL,
    app_user_id BIGINT NOT NULL,
    instance_id CHAR(36) NULL,
    person_template_id CHAR(36) NOT NULL,
    wardrobe_item_id BIGINT NOT NULL,
    person_asset_version_id BIGINT NOT NULL,
    garment_asset_version_id BIGINT NOT NULL,
    task_status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    attempt_count INT NOT NULL DEFAULT 0,
    output_asset_version_id BIGINT NULL,
    failure_summary VARCHAR(512) NOT NULL DEFAULT '',
    claimed_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fashion_tryon_tasks_dispatch (task_status, created_at),
    KEY idx_fashion_tryon_tasks_user_created (app_user_id, created_at DESC),
    KEY idx_fashion_tryon_tasks_template (person_template_id, created_at DESC),
    CONSTRAINT fk_fashion_tryon_task_user
        FOREIGN KEY (app_user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_tryon_task_instance
        FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE SET NULL,
    CONSTRAINT fk_fashion_tryon_task_template
        FOREIGN KEY (person_template_id) REFERENCES fashion_person_templates(id) ON DELETE RESTRICT,
    CONSTRAINT fk_fashion_tryon_task_wardrobe_item
        FOREIGN KEY (wardrobe_item_id) REFERENCES fashion_wardrobe_items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_fashion_tryon_task_person_asset
        FOREIGN KEY (person_asset_version_id) REFERENCES asset_versions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_fashion_tryon_task_garment_asset
        FOREIGN KEY (garment_asset_version_id) REFERENCES asset_versions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_fashion_tryon_task_output_asset
        FOREIGN KEY (output_asset_version_id) REFERENCES asset_versions(id) ON DELETE SET NULL,
    CONSTRAINT chk_fashion_tryon_task_status
        CHECK (task_status IN ('SUBMITTED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_fashion_tryon_task_attempt_count
        CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
