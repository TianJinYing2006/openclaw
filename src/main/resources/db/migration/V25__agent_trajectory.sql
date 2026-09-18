-- ====================================================================
-- Agent 轨迹（Agent Trajectory）：把一次穿搭图执行按 run 分组记录，
-- 供管理站回放、按节点聚合耗时/Token、定位失败与降级。
--
-- 与 ai_usage_events 的区别：ai_usage_events 是「原子调用事件」（无 run 关联、
-- 不记录节点归属）；本表记录 run + step 两级结构，step 带 node_name / step_type，
-- 可回答「哪个节点最费 Token / 哪个节点最易失败 / 失败后是否降级」。
-- run_id 同时作为 spring-ai-alibaba-graph 的 threadId 关联键。
-- ====================================================================

CREATE TABLE IF NOT EXISTS agent_runs (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    run_id            CHAR(36)     NOT NULL,
    thread_id         VARCHAR(191) NOT NULL DEFAULT '',
    external_user_id  VARCHAR(255) NOT NULL DEFAULT '',
    query_text        VARCHAR(1000) NOT NULL DEFAULT '',
    graph_version     VARCHAR(64)  NOT NULL DEFAULT '',
    prompt_version    VARCHAR(64)  NOT NULL DEFAULT '',
    model             VARCHAR(128) NOT NULL DEFAULT '',
    status            VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
    total_tokens      BIGINT       NOT NULL DEFAULT 0,
    duration_ms       BIGINT       NOT NULL DEFAULT 0,
    error_message     VARCHAR(500) NOT NULL DEFAULT '',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_runs_run_id (run_id),
    KEY idx_agent_runs_user_created (external_user_id, created_at DESC),
    KEY idx_agent_runs_status_created (status, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_run_steps (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    run_id            CHAR(36)     NOT NULL,
    seq               INT          NOT NULL DEFAULT 0,
    node_name         VARCHAR(64)  NOT NULL DEFAULT '',
    step_type         VARCHAR(16)  NOT NULL DEFAULT 'NODE',
    status            VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
    model             VARCHAR(128) NOT NULL DEFAULT '',
    tool_name         VARCHAR(128) NOT NULL DEFAULT '',
    input_summary     VARCHAR(1000) NOT NULL DEFAULT '',
    output_summary    VARCHAR(1000) NOT NULL DEFAULT '',
    prompt_tokens     BIGINT       NOT NULL DEFAULT 0,
    completion_tokens BIGINT       NOT NULL DEFAULT 0,
    total_tokens      BIGINT       NOT NULL DEFAULT 0,
    duration_ms       BIGINT       NOT NULL DEFAULT 0,
    error_message     VARCHAR(500) NOT NULL DEFAULT '',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_run_steps_run (run_id, seq),
    KEY idx_agent_run_steps_node (node_name, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
