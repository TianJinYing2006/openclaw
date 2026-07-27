-- A chat identity belongs to exactly one managed bot instance. Keep that relationship indexed instead of
-- repeatedly deriving it from the legacy external_user_id prefix at query time.
ALTER TABLE app_users
    ADD COLUMN platform_user_id BIGINT NULL AFTER id,
    ADD COLUMN instance_id CHAR(36) NULL AFTER platform_user_id,
    ADD KEY idx_app_users_instance_seen (instance_id, last_seen_at DESC),
    ADD KEY idx_app_users_platform_seen (platform_user_id, last_seen_at DESC);

UPDATE app_users a
JOIN bot_instances b ON a.external_user_id LIKE CONCAT('managed:', b.id, ':%')
SET a.platform_user_id = COALESCE(a.platform_user_id, b.platform_user_id),
    a.instance_id = COALESCE(a.instance_id, b.id)
WHERE a.external_user_id LIKE 'managed:%'
  AND (a.platform_user_id IS NULL OR a.instance_id IS NULL);
