-- All future schedules use the Agent execution path. Upgrade unsent legacy reminders so they do not
-- degrade to fixed text after the runtime switch. Completed history remains unchanged.
UPDATE reminders
SET execution_mode = 'AGENT',
    task_prompt = CASE
        WHEN task_prompt IS NULL OR task_prompt = '' THEN reminder_text
        ELSE task_prompt
    END
WHERE status = 'ACTIVE' AND execution_mode = 'MESSAGE';

UPDATE reminder_deliveries d
JOIN reminders r ON r.id = d.reminder_id
SET d.execution_mode = 'AGENT',
    d.task_prompt_snapshot = CASE
        WHEN d.task_prompt_snapshot IS NULL OR d.task_prompt_snapshot = '' THEN r.task_prompt
        ELSE d.task_prompt_snapshot
    END
WHERE d.status IN ('PENDING', 'RETRY', 'WAITING_CONTEXT', 'PROCESSING')
  AND d.execution_mode = 'MESSAGE';
