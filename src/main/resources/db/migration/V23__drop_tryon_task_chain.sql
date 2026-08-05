-- 链式整套试穿方案已下线（拼图一次出图替代，见 docs/fashion-agent-design.md 8.5）。
-- 先终止存量链式任务，避免后台按链式渲染已删除的逻辑；随后删除列。
UPDATE fashion_virtual_tryon_tasks
SET task_status = 'FAILED',
    failure_summary = '链式试穿方案已下线，请重新发起试穿'
WHERE chain_garments_json IS NOT NULL AND chain_garments_json <> '';

ALTER TABLE fashion_virtual_tryon_tasks
    DROP COLUMN chain_garments_json;
