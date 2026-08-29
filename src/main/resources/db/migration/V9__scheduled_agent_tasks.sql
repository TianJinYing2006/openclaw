-- Existing reminders remain plain MESSAGE deliveries. AGENT deliveries store the original intent
-- and re-enter the normal tool-calling chain when their scheduled occurrence becomes due.
ALTER TABLE reminders
    ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'MESSAGE' AFTER reminder_text,
    ADD COLUMN task_prompt TEXT NULL AFTER execution_mode;

ALTER TABLE reminder_deliveries
    ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'MESSAGE' AFTER message_snapshot,
    ADD COLUMN task_prompt_snapshot TEXT NULL AFTER execution_mode;
