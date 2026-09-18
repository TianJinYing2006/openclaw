-- ====================================================================
-- 记忆治理：给用户偏好加「作用域 + 过期时间」。
-- 背景：LLM 可能把一次性表达（"这次不要红色"）误记为长期偏好。
-- 用 scope 区分长期偏好与本次/短期上下文，expires_at 让短期偏好自动失效。
-- ====================================================================

ALTER TABLE fashion_user_preferences
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'LONG_TERM' AFTER source,
    ADD COLUMN expires_at TIMESTAMP NULL AFTER last_evidence;

CREATE INDEX idx_fashion_user_preferences_expiry ON fashion_user_preferences (app_user_id, expires_at);
