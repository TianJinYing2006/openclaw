-- Shared garment facts plus a platform-owned outfit reference library.
-- Public references are not products: they intentionally have no price, stock or purchase URL.

ALTER TABLE fashion_wardrobe_items
    ADD COLUMN display_name VARCHAR(128) NOT NULL DEFAULT '' AFTER instance_id,
    ADD COLUMN parent_category_code VARCHAR(64) NOT NULL DEFAULT '' AFTER display_name,
    ADD COLUMN annotation_schema_version VARCHAR(16) NOT NULL DEFAULT '1.0.0' AFTER notes,
    ADD COLUMN attributes_json JSON NULL AFTER annotation_schema_version;

UPDATE fashion_wardrobe_items item
LEFT JOIN fashion_taxonomy_nodes taxonomy ON taxonomy.code = item.category_code
SET item.parent_category_code = COALESCE(NULLIF(taxonomy.parent_code, ''), item.category_code)
WHERE item.parent_category_code = '';

CREATE TABLE IF NOT EXISTS fashion_reference_looks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reference_code VARCHAR(160) NOT NULL,
    display_name VARCHAR(255) NOT NULL DEFAULT '',
    image_asset_id VARCHAR(64) NOT NULL DEFAULT '',
    image_asset_version INT NOT NULL DEFAULT 0,
    image_media_type VARCHAR(64) NOT NULL DEFAULT 'image/png',
    source_file_name VARCHAR(255) NOT NULL,
    source_url VARCHAR(2048) NOT NULL DEFAULT '',
    source_site VARCHAR(128) NOT NULL DEFAULT '',
    usage_rights VARCHAR(64) NOT NULL DEFAULT 'UNVERIFIED',
    sha256 CHAR(64) NOT NULL,
    phash VARCHAR(64) NOT NULL DEFAULT '',
    annotation_schema_version VARCHAR(16) NOT NULL DEFAULT '1.0.0',
    annotation_json JSON NOT NULL,
    reference_status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_reference_look_code (reference_code),
    UNIQUE KEY uk_fashion_reference_look_sha256 (sha256),
    KEY idx_fashion_reference_look_status (reference_status, updated_at DESC),
    CONSTRAINT chk_fashion_reference_look_status
        CHECK (reference_status IN ('DRAFT', 'ACTIVE', 'NEEDS_REVIEW', 'ARCHIVED')),
    CONSTRAINT chk_fashion_reference_look_asset_version CHECK (image_asset_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_reference_garments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reference_look_id BIGINT NOT NULL,
    item_index INT NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    sub_category_code VARCHAR(64) NOT NULL,
    target_gender VARCHAR(16) NOT NULL DEFAULT 'UNISEX',
    color_primary VARCHAR(64) NOT NULL DEFAULT '',
    color_secondary_json JSON NULL,
    accent_colors_json JSON NULL,
    style_tags_json JSON NULL,
    fit_code VARCHAR(64) NOT NULL DEFAULT '',
    pattern_code VARCHAR(64) NOT NULL DEFAULT '',
    silhouette_code VARCHAR(64) NOT NULL DEFAULT '',
    length_code VARCHAR(64) NOT NULL DEFAULT '',
    material_tags_json JSON NULL,
    season_tags_json JSON NULL,
    occasion_tags_json JSON NULL,
    formality_level TINYINT UNSIGNED NOT NULL DEFAULT 0,
    visibility_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    visible_ratio DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    attributes_json JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_reference_garment_index (reference_look_id, item_index),
    KEY idx_fashion_reference_garment_category (sub_category_code, color_primary),
    KEY idx_fashion_reference_garment_parent (category_code, color_primary),
    CONSTRAINT fk_fashion_reference_garment_look
        FOREIGN KEY (reference_look_id) REFERENCES fashion_reference_looks(id) ON DELETE CASCADE,
    CONSTRAINT chk_fashion_reference_gender
        CHECK (target_gender IN ('UNISEX', 'MENS', 'WOMENS', 'UNKNOWN')),
    CONSTRAINT chk_fashion_reference_formality CHECK (formality_level <= 5),
    CONSTRAINT chk_fashion_reference_visibility CHECK (visible_ratio >= 0 AND visible_ratio <= 1),
    CONSTRAINT chk_fashion_reference_confidence CHECK (confidence >= 0 AND confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_reference_semantic_index_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reference_look_id BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL DEFAULT 'UPSERT',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMP NULL,
    content_hash CHAR(64) NOT NULL DEFAULT '',
    failure_summary VARCHAR(512) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_reference_semantic_job_look (reference_look_id),
    KEY idx_fashion_reference_semantic_dispatch (status, next_attempt_at, id),
    CONSTRAINT fk_fashion_reference_semantic_look
        FOREIGN KEY (reference_look_id) REFERENCES fashion_reference_looks(id) ON DELETE CASCADE,
    CONSTRAINT chk_fashion_reference_semantic_operation
        CHECK (operation IN ('UPSERT', 'DELETE')),
    CONSTRAINT chk_fashion_reference_semantic_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Existing wardrobe points predate scope metadata. Rebuild them so private and public searches cannot mix.
UPDATE fashion_semantic_index_jobs
SET status = 'PENDING', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP,
    lease_until = NULL, failure_summary = '';
