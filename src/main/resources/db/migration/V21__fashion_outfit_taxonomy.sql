-- 支持"整套穿搭"作为一个整体候选入库：用户上传整套照片时识别只返回
-- 一个 OUTFIT 候选（上衣+裤子等成套保存），不再拆成多件单独抠图。
INSERT INTO fashion_taxonomy_nodes(code, parent_code, name, node_type, node_level, sort_order)
VALUES ('OUTFIT', NULL, '整套穿搭', 'CATEGORY', 1, 80)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    parent_code = VALUES(parent_code),
    node_type = VALUES(node_type),
    node_level = VALUES(node_level),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE';
