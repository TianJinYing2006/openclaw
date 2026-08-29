-- Each model attempt is already an append-only usage event. Keep its failure category as well,
-- so the local admin can distinguish successful calls from failed upstream attempts.
ALTER TABLE ai_usage_events
    ADD COLUMN failure_reason VARCHAR(128) NOT NULL DEFAULT '' AFTER reported;

CREATE INDEX idx_ai_usage_events_external_created ON ai_usage_events (external_user_id, created_at DESC);
CREATE INDEX idx_ai_usage_events_kind_created ON ai_usage_events (usage_kind, created_at DESC);
