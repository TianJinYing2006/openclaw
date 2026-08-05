-- 整套试穿链式支持：主 garment 之后的可选后续单品（依次渲染，前一步输出作为下一步人物图，最终只回一张图）。
-- chain_garments_json 为 JSON 数组：[{"assetVersionId":123,"categoryCode":"STRAIGHT_PANTS"}, ...]；NULL 表示单件任务。
ALTER TABLE fashion_virtual_tryon_tasks
    ADD COLUMN chain_garments_json TEXT NULL COMMENT '整套试穿后续单品清单(JSON数组)，NULL=单件任务' AFTER garment_category_code;
