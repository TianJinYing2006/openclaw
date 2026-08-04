-- 支持"参考穿搭图试衣"：推荐方案的单品图（来自 RAG 知识库）不经过用户衣橱，
-- 直接以外部参考图为素材创建试衣任务。
-- wardrobe_item_id 允许为空（参考图没有对应衣橱单品）；新增来源标识、推荐方案编号与类目。
ALTER TABLE fashion_virtual_tryon_tasks
    MODIFY wardrobe_item_id BIGINT NULL,
    ADD COLUMN garment_source VARCHAR(16) NOT NULL DEFAULT 'wardrobe' AFTER garment_asset_version_id,
    ADD COLUMN reference_outfit_id VARCHAR(64) NULL AFTER garment_source,
    ADD COLUMN garment_category_code VARCHAR(32) NULL AFTER reference_outfit_id;
