-- 记录每次穿搭推荐命中的 outfit 编号，供后续"试穿一下"精确指代最近推荐方案，
-- 避免模型为试穿重新走 fashion_consultant 生成一套新方案并刷出新图片。
ALTER TABLE fashion_conversations
    ADD COLUMN reference_outfit_id VARCHAR(16) NOT NULL DEFAULT '' AFTER recommendation;
