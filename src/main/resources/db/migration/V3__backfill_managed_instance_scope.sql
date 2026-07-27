-- Keep the legacy external_user_id namespace for compatibility, while making ownership queryable by indexed columns.
UPDATE chat_conversations c
JOIN bot_instances b ON c.external_user_id LIKE CONCAT('managed:', b.id, ':%')
SET c.platform_user_id = COALESCE(c.platform_user_id, b.platform_user_id),
    c.instance_id = COALESCE(c.instance_id, b.id)
WHERE c.platform_user_id IS NULL OR c.instance_id IS NULL;

UPDATE async_tasks t
JOIN bot_instances b ON t.external_user_id LIKE CONCAT('managed:', b.id, ':%')
SET t.platform_user_id = COALESCE(t.platform_user_id, b.platform_user_id),
    t.instance_id = COALESCE(t.instance_id, b.id)
WHERE t.platform_user_id IS NULL OR t.instance_id IS NULL;

UPDATE asset_versions a
JOIN bot_instances b ON a.external_user_id LIKE CONCAT('managed:', b.id, ':%')
SET a.platform_user_id = COALESCE(a.platform_user_id, b.platform_user_id),
    a.instance_id = COALESCE(a.instance_id, b.id)
WHERE a.platform_user_id IS NULL OR a.instance_id IS NULL;

UPDATE ai_usage_events u
JOIN bot_instances b ON u.external_user_id LIKE CONCAT('managed:', b.id, ':%')
SET u.platform_user_id = COALESCE(u.platform_user_id, b.platform_user_id),
    u.instance_id = COALESCE(u.instance_id, b.id)
WHERE u.platform_user_id IS NULL OR u.instance_id IS NULL;
