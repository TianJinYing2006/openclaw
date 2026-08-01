-- Fashion catalog Phase 2: platform-owned products are separate from user-owned wardrobe items.
-- The initial catalog is intentionally small and unisex; real suppliers can later import into this same model.

CREATE TABLE IF NOT EXISTS fashion_products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_code VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    brand VARCHAR(128) NOT NULL DEFAULT '',
    category_code VARCHAR(64) NOT NULL,
    sub_category_code VARCHAR(64) NOT NULL,
    gender_target VARCHAR(16) NOT NULL DEFAULT 'UNISEX',
    color_primary VARCHAR(64) NOT NULL DEFAULT '',
    color_secondary_json JSON NULL,
    style_tags_json JSON NULL,
    season_tags_json JSON NULL,
    occasion_tags_json JSON NULL,
    material VARCHAR(128) NOT NULL DEFAULT '',
    fit_code VARCHAR(64) NOT NULL DEFAULT '',
    pattern_code VARCHAR(64) NOT NULL DEFAULT '',
    price DECIMAL(10,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    availability_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    featured_rank INT NOT NULL DEFAULT 0,
    source VARCHAR(32) NOT NULL DEFAULT 'ADMIN_CATALOG',
    source_product_id VARCHAR(128) NOT NULL DEFAULT '',
    source_url VARCHAR(2048) NOT NULL DEFAULT '',
    text_description TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_products_code (product_code),
    KEY idx_fashion_products_listing (availability_status, featured_rank DESC, updated_at DESC),
    KEY idx_fashion_products_filter (sub_category_code, gender_target, price, availability_status),
    KEY idx_fashion_products_brand (brand),
    CONSTRAINT fk_fashion_products_category
        FOREIGN KEY (category_code) REFERENCES fashion_taxonomy_nodes(code),
    CONSTRAINT fk_fashion_products_sub_category
        FOREIGN KEY (sub_category_code) REFERENCES fashion_taxonomy_nodes(code),
    CONSTRAINT chk_fashion_products_gender
        CHECK (gender_target IN ('UNISEX', 'FEMALE', 'MALE')),
    CONSTRAINT chk_fashion_products_price CHECK (price >= 0),
    CONSTRAINT chk_fashion_products_status
        CHECK (availability_status IN ('DRAFT', 'ACTIVE', 'OUT_OF_STOCK', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS fashion_product_images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    image_url VARCHAR(2048) NOT NULL,
    image_role VARCHAR(32) NOT NULL DEFAULT 'PRIMARY',
    sort_order INT NOT NULL DEFAULT 0,
    sha256 CHAR(64) NULL,
    phash VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_product_image_role (product_id, image_role),
    KEY idx_fashion_product_images_hash (sha256),
    CONSTRAINT fk_fashion_product_images_product
        FOREIGN KEY (product_id) REFERENCES fashion_products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO fashion_products(
    product_code, title, brand, category_code, sub_category_code, gender_target, color_primary,
    color_secondary_json, style_tags_json, season_tags_json, occasion_tags_json, material, fit_code, pattern_code,
    price, currency, availability_status, featured_rank, source, text_description
) VALUES
    ('UNX-TEE-WHITE', '基础白色棉质 T 恤', 'YKD Basics', 'TOP', 'T_SHIRT', 'UNISEX', '白色',
     JSON_ARRAY(), JSON_ARRAY('简约', '休闲'), JSON_ARRAY('春夏'), JSON_ARRAY('通勤', '日常'), '棉', '常规', '纯色',
     129.00, 'CNY', 'ACTIVE', 100, 'SEED_CATALOG', '简约圆领棉质 T 恤，可作为通勤和日常叠穿基础款。'),
    ('UNX-TEE-BLACK', '基础黑色棉质 T 恤', 'YKD Basics', 'TOP', 'T_SHIRT', 'UNISEX', '黑色',
     JSON_ARRAY(), JSON_ARRAY('简约', '休闲'), JSON_ARRAY('春夏'), JSON_ARRAY('通勤', '日常'), '棉', '常规', '纯色',
     129.00, 'CNY', 'ACTIVE', 99, 'SEED_CATALOG', '低饱和黑色基础 T 恤，适合简约和层次搭配。'),
    ('UNX-SHIRT-OXFORD', '浅蓝牛津纺衬衫', 'YKD Basics', 'TOP', 'SHIRT', 'UNISEX', '浅蓝色',
     JSON_ARRAY(), JSON_ARRAY('通勤', '学院'), JSON_ARRAY('春秋', '夏季'), JSON_ARRAY('通勤', '面试', '约会'), '棉', '宽松', '纯色',
     269.00, 'CNY', 'ACTIVE', 95, 'SEED_CATALOG', '宽松浅蓝牛津纺衬衫，可单穿或作为轻外套。'),
    ('UNX-SHIRT-DENIM', '水洗牛仔衬衫', 'YKD Basics', 'TOP', 'SHIRT', 'UNISEX', '牛仔蓝',
     JSON_ARRAY(), JSON_ARRAY('工装', '休闲'), JSON_ARRAY('春秋'), JSON_ARRAY('日常', '出行'), '牛仔布', '宽松', '纯色',
     329.00, 'CNY', 'ACTIVE', 80, 'SEED_CATALOG', '中等水洗效果的牛仔衬衫，适合与白色内搭和直筒裤组合。'),
    ('UNX-KNIT-NAVY', '藏青圆领针织衫', 'YKD Basics', 'TOP', 'KNITWEAR', 'UNISEX', '藏青色',
     JSON_ARRAY(), JSON_ARRAY('简约', '通勤'), JSON_ARRAY('秋冬', '春秋'), JSON_ARRAY('通勤', '面试'), '棉混纺', '常规', '纯色',
     359.00, 'CNY', 'ACTIVE', 90, 'SEED_CATALOG', '低调藏青圆领针织衫，适合需要稍正式但不过度严肃的场景。'),
    ('UNX-JACKET-OLIVE', '橄榄绿轻量夹克', 'YKD Basics', 'OUTERWEAR', 'JACKET', 'UNISEX', '橄榄绿',
     JSON_ARRAY(), JSON_ARRAY('工装', '休闲'), JSON_ARRAY('春秋'), JSON_ARRAY('通勤', '出行'), '尼龙混纺', '宽松', '纯色',
     499.00, 'CNY', 'ACTIVE', 75, 'SEED_CATALOG', '轻量短夹克，适合春秋叠穿和通勤出行。'),
    ('UNX-JEANS-INDIGO', '靛蓝直筒牛仔裤', 'YKD Basics', 'BOTTOM', 'JEANS', 'UNISEX', '靛蓝色',
     JSON_ARRAY(), JSON_ARRAY('简约', '休闲'), JSON_ARRAY('四季'), JSON_ARRAY('通勤', '日常'), '牛仔布', '直筒', '纯色',
     399.00, 'CNY', 'ACTIVE', 100, 'SEED_CATALOG', '中高腰直筒版型牛仔裤，适合作为男女通用的日常基础下装。'),
    ('UNX-PANTS-BLACK', '黑色垂感直筒裤', 'YKD Basics', 'BOTTOM', 'STRAIGHT_PANTS', 'UNISEX', '黑色',
     JSON_ARRAY(), JSON_ARRAY('通勤', '简约'), JSON_ARRAY('四季'), JSON_ARRAY('通勤', '面试', '约会'), '聚酯混纺', '直筒', '纯色',
     429.00, 'CNY', 'ACTIVE', 98, 'SEED_CATALOG', '垂感面料黑色直筒裤，可与衣橱中多件上装复用。'),
    ('UNX-SNEAKER-WHITE', '白色低帮运动鞋', 'YKD Basics', 'SHOES', 'SNEAKERS', 'UNISEX', '白色',
     JSON_ARRAY(), JSON_ARRAY('休闲', '简约'), JSON_ARRAY('四季'), JSON_ARRAY('通勤', '日常', '出行'), '合成革', '常规', '纯色',
     459.00, 'CNY', 'ACTIVE', 85, 'SEED_CATALOG', '干净的白色低帮鞋，适合和牛仔裤、直筒裤及轻外套搭配。'),
    ('UNX-LOAFER-BLACK', '黑色轻便乐福鞋', 'YKD Basics', 'SHOES', 'LOAFERS', 'UNISEX', '黑色',
     JSON_ARRAY(), JSON_ARRAY('通勤', '简约'), JSON_ARRAY('春秋', '夏季'), JSON_ARRAY('通勤', '面试', '约会'), '超纤革', '常规', '纯色',
     529.00, 'CNY', 'ACTIVE', 70, 'SEED_CATALOG', '轻便黑色乐福鞋，为通勤和轻正式穿搭提供更稳重的鞋履选择。'),
    ('UNX-BAG-BLACK', '黑色尼龙斜挎包', 'YKD Basics', 'BAG', 'BAG', 'UNISEX', '黑色',
     JSON_ARRAY(), JSON_ARRAY('简约', '机能'), JSON_ARRAY('四季'), JSON_ARRAY('通勤', '出行'), '尼龙', '常规', '纯色',
     299.00, 'CNY', 'ACTIVE', 60, 'SEED_CATALOG', '容量适中的黑色斜挎包，适合作为日常通勤配件。')
ON DUPLICATE KEY UPDATE product_code = VALUES(product_code);
