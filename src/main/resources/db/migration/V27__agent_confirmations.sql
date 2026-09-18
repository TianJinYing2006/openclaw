-- ====================================================================
-- HITL 确认记录：为「暂停等待用户确认」的操作提供幂等与可运营能力。
-- 同一 (run_id, action) 唯一，避免重复创建确认；status/confirmed_at/result_summary
-- 支持「恢复重放返回同一结果」，防止重复执行付费/副作用操作。
-- 管理站可据此展示「待确认任务列表」。
-- ====================================================================

CREATE TABLE IF NOT EXISTS agent_confirmations (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    confirmation_id CHAR(36)     NOT NULL,
    run_id          VARCHAR(191) NOT NULL,
    user_id         VARCHAR(255) NOT NULL DEFAULT '',
    action          VARCHAR(64)  NOT NULL DEFAULT 'PAID_OPERATION',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    result_summary  VARCHAR(1000) NOT NULL DEFAULT '',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at    TIMESTAMP    NULL,
    expires_at      TIMESTAMP    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_confirmations_confirmation (confirmation_id),
    UNIQUE KEY uk_agent_confirmations_run_action (run_id, action),
    KEY idx_agent_confirmations_status (status, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
