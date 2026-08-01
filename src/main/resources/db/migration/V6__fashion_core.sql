-- Fashion Phase 1: durable profile, wardrobe and clothing-analysis facts.
-- app_users remains the canonical end-chat-user identity; platform_users owns bot instances only.

CREATE TABLE IF NOT EXISTS fashion_taxonomy_nodes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    parent_code VARCHAR(64) NULL,
    name VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL DEFAULT 'CATEGORY',
    node_level INT NOT NULL DEFAULT 1,
    synonyms_json JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_taxonomy_nodes_code (code),
    KEY idx_fashion_taxonomy_parent (parent_code, sort_order),
    KEY idx_fashion_taxonomy_status (status, node_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_user_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app_user_id BIGINT NOT NULL,
    gender_expression VARCHAR(32) NOT NULL DEFAULT '',
    style_summary VARCHAR(512) NOT NULL DEFAULT '',
    budget_min DECIMAL(10,2) NULL,
    budget_max DECIMAL(10,2) NULL,
    common_occasions_json JSON NULL,
    profile_completeness TINYINT UNSIGNED NOT NULL DEFAULT 0,
    privacy_consent_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_user_profiles_app_user (app_user_id),
    CONSTRAINT fk_fashion_user_profiles_app_user
        FOREIGN KEY (app_user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT chk_fashion_user_profile_budget
        CHECK (budget_min IS NULL OR budget_max IS NULL OR budget_min <= budget_max),
    CONSTRAINT chk_fashion_user_profile_completeness
        CHECK (profile_completeness <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_user_preferences (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app_user_id BIGINT NOT NULL,
    dimension_code VARCHAR(32) NOT NULL,
    value_code VARCHAR(128) NOT NULL,
    polarity VARCHAR(16) NOT NULL DEFAULT 'POSITIVE',
    weight DECIMAL(5,2) NOT NULL DEFAULT 1.00,
    confidence DECIMAL(5,2) NOT NULL DEFAULT 1.00,
    source VARCHAR(32) NOT NULL DEFAULT 'USER_DECLARED',
    last_evidence VARCHAR(512) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_user_preference_current (app_user_id, dimension_code, value_code, polarity),
    KEY idx_fashion_user_preferences_lookup (app_user_id, dimension_code, polarity),
    CONSTRAINT fk_fashion_user_preferences_app_user
        FOREIGN KEY (app_user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT chk_fashion_user_preference_polarity
        CHECK (polarity IN ('POSITIVE', 'NEGATIVE')),
    CONSTRAINT chk_fashion_user_preference_weight
        CHECK (weight >= 0 AND weight <= 100),
    CONSTRAINT chk_fashion_user_preference_confidence
        CHECK (confidence >= 0 AND confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_wardrobe_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app_user_id BIGINT NOT NULL,
    instance_id CHAR(36) NULL,
    category_code VARCHAR(64) NOT NULL,
    color_primary VARCHAR(64) NOT NULL DEFAULT '',
    color_secondary_json JSON NULL,
    style_tags_json JSON NULL,
    fit_code VARCHAR(64) NOT NULL DEFAULT '',
    pattern_code VARCHAR(64) NOT NULL DEFAULT '',
    season_tags_json JSON NULL,
    occasion_tags_json JSON NULL,
    material VARCHAR(128) NOT NULL DEFAULT '',
    item_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    analysis_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    analysis_version INT NOT NULL DEFAULT 0,
    attribute_confidence DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    source VARCHAR(32) NOT NULL DEFAULT 'USER_UPLOAD',
    notes VARCHAR(512) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fashion_wardrobe_user_active_category (app_user_id, item_status, category_code),
    KEY idx_fashion_wardrobe_instance_updated (instance_id, updated_at DESC),
    CONSTRAINT fk_fashion_wardrobe_app_user
        FOREIGN KEY (app_user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_wardrobe_instance
        FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE SET NULL,
    CONSTRAINT fk_fashion_wardrobe_category
        FOREIGN KEY (category_code) REFERENCES fashion_taxonomy_nodes(code),
    CONSTRAINT chk_fashion_wardrobe_status
        CHECK (item_status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_fashion_wardrobe_confidence
        CHECK (attribute_confidence >= 0 AND attribute_confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_wardrobe_item_assets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    wardrobe_item_id BIGINT NOT NULL,
    asset_version_id BIGINT NOT NULL,
    asset_role VARCHAR(32) NOT NULL DEFAULT 'PRIMARY',
    is_primary BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_wardrobe_asset (wardrobe_item_id, asset_version_id),
    KEY idx_fashion_wardrobe_assets_asset (asset_version_id),
    CONSTRAINT fk_fashion_wardrobe_assets_item
        FOREIGN KEY (wardrobe_item_id) REFERENCES fashion_wardrobe_items(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_wardrobe_assets_version
        FOREIGN KEY (asset_version_id) REFERENCES asset_versions(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_clothing_analyses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app_user_id BIGINT NOT NULL,
    instance_id CHAR(36) NULL,
    asset_version_id BIGINT NOT NULL,
    wardrobe_item_id BIGINT NULL,
    category_code VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN',
    attributes_json JSON NULL,
    confidence DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    analysis_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    provider VARCHAR(64) NOT NULL DEFAULT '',
    model VARCHAR(128) NOT NULL DEFAULT '',
    prompt_version VARCHAR(64) NOT NULL DEFAULT '',
    analysis_version INT NOT NULL DEFAULT 1,
    failure_summary VARCHAR(512) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_analysis_asset_version (asset_version_id, analysis_version),
    KEY idx_fashion_analysis_user_created (app_user_id, created_at DESC),
    KEY idx_fashion_analysis_status_created (analysis_status, created_at),
    CONSTRAINT fk_fashion_analysis_app_user
        FOREIGN KEY (app_user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_fashion_analysis_instance
        FOREIGN KEY (instance_id) REFERENCES bot_instances(id) ON DELETE SET NULL,
    CONSTRAINT fk_fashion_analysis_asset_version
        FOREIGN KEY (asset_version_id) REFERENCES asset_versions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_fashion_analysis_wardrobe_item
        FOREIGN KEY (wardrobe_item_id) REFERENCES fashion_wardrobe_items(id) ON DELETE SET NULL,
    CONSTRAINT fk_fashion_analysis_category
        FOREIGN KEY (category_code) REFERENCES fashion_taxonomy_nodes(code),
    CONSTRAINT chk_fashion_analysis_confidence
        CHECK (confidence >= 0 AND confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO fashion_taxonomy_nodes(code, parent_code, name, node_type, node_level, sort_order)
VALUES
    ('UNKNOWN', NULL, '未知单品', 'CATEGORY', 1, 0),
    ('TOP', NULL, '上装', 'CATEGORY', 1, 10),
    ('T_SHIRT', 'TOP', 'T恤', 'CATEGORY', 2, 11),
    ('SHIRT', 'TOP', '衬衫', 'CATEGORY', 2, 12),
    ('KNITWEAR', 'TOP', '针织衫', 'CATEGORY', 2, 13),
    ('OUTERWEAR', NULL, '外套', 'CATEGORY', 1, 20),
    ('JACKET', 'OUTERWEAR', '夹克', 'CATEGORY', 2, 21),
    ('BOTTOM', NULL, '下装', 'CATEGORY', 1, 30),
    ('JEANS', 'BOTTOM', '牛仔裤', 'CATEGORY', 2, 31),
    ('STRAIGHT_PANTS', 'BOTTOM', '直筒裤', 'CATEGORY', 2, 32),
    ('SKIRT', 'BOTTOM', '半身裙', 'CATEGORY', 2, 33),
    ('DRESS', NULL, '连衣裙', 'CATEGORY', 1, 40),
    ('SHOES', NULL, '鞋履', 'CATEGORY', 1, 50),
    ('SNEAKERS', 'SHOES', '运动鞋', 'CATEGORY', 2, 51),
    ('LOAFERS', 'SHOES', '乐福鞋', 'CATEGORY', 2, 52),
    ('BAG', NULL, '包袋', 'CATEGORY', 1, 60),
    ('ACCESSORY', NULL, '配饰', 'CATEGORY', 1, 70)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    parent_code = VALUES(parent_code),
    node_type = VALUES(node_type),
    node_level = VALUES(node_level),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE';
