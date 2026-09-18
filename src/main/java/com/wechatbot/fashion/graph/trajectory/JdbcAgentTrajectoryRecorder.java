package com.wechatbot.fashion.graph.trajectory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AgentTrajectoryRecorder} 的 JDBC 实现（表 {@code agent_runs} / {@code agent_run_steps}，V25）。
 *
 * <p>与 {@code JdbcUsageEventRecorder} 一致：组件无条件装配，通过 {@link ObjectProvider} 容忍
 * 持久化关闭；任何写库异常只告警不抛出，保证轨迹记录永不影响穿搭主链路。</p>
 */
@Component
public class JdbcAgentTrajectoryRecorder implements AgentTrajectoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentTrajectoryRecorder.class);

    private static final int MAX_QUERY = 1000;
    private static final int MAX_SUMMARY = 1000;
    private static final int MAX_ERROR = 500;

    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    /** runId -> 步骤序号，保证同一 run 内 step 有序；finishRun 时清理。 */
    private final ConcurrentHashMap<String, AtomicInteger> stepSeq = new ConcurrentHashMap<>();

    public JdbcAgentTrajectoryRecorder(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    @Override
    public void startRun(RunStart start) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null || start == null || start.runId() == null) {
            return;
        }
        stepSeq.put(start.runId(), new AtomicInteger(0));
        try {
            jdbc.update("""
                    INSERT INTO agent_runs(run_id, thread_id, external_user_id, query_text,
                        graph_version, prompt_version, model, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING')
                    ON DUPLICATE KEY UPDATE run_id = run_id
                    """,
                    start.runId(),
                    truncate(start.threadId(), 191),
                    truncate(start.userId(), 255),
                    truncate(start.query(), MAX_QUERY),
                    truncate(start.graphVersion(), 64),
                    truncate(start.promptVersion(), 64),
                    truncate(start.model(), 128));
        } catch (RuntimeException e) {
            log.warn("agent-run start not recorded: {}", e.getMessage());
            stepSeq.remove(start.runId());
        }
    }

    @Override
    public void recordStep(Step step) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null || step == null || step.runId() == null) {
            return;
        }
        int seq = stepSeq.computeIfAbsent(step.runId(), k -> new AtomicInteger(0)).incrementAndGet();
        try {
            jdbc.update("""
                    INSERT INTO agent_run_steps(run_id, seq, node_name, step_type, status, model, tool_name,
                        input_summary, output_summary, prompt_tokens, completion_tokens, total_tokens,
                        duration_ms, error_message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    step.runId(),
                    seq,
                    truncate(step.nodeName(), 64),
                    truncate(step.stepType(), 16),
                    truncate(step.status(), 16),
                    truncate(step.model(), 128),
                    truncate(step.toolName(), 128),
                    truncate(step.inputSummary(), MAX_SUMMARY),
                    truncate(step.outputSummary(), MAX_SUMMARY),
                    Math.max(0, step.promptTokens()),
                    Math.max(0, step.completionTokens()),
                    Math.max(0, step.totalTokens()),
                    Math.max(0, step.durationMs()),
                    truncate(step.errorMessage(), MAX_ERROR));
        } catch (RuntimeException e) {
            log.warn("agent-run step not recorded: {}", e.getMessage());
        }
    }

    @Override
    public void finishRun(String runId, String status, long durationMs, String errorMessage) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null || runId == null) {
            return;
        }
        try {
            jdbc.update("""
                    UPDATE agent_runs
                    SET status = ?, duration_ms = ?, error_message = ?,
                        total_tokens = (SELECT COALESCE(SUM(total_tokens), 0) FROM agent_run_steps s WHERE s.run_id = ?)
                    WHERE run_id = ?
                    """,
                    truncate(status, 32),
                    Math.max(0, durationMs),
                    truncate(errorMessage, MAX_ERROR),
                    runId,
                    runId);
        } catch (RuntimeException e) {
            log.warn("agent-run finish not recorded: {}", e.getMessage());
        } finally {
            stepSeq.remove(runId);
        }
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String single = value.replace('\n', ' ').replace('\r', ' ').strip();
        return single.length() <= max ? single : single.substring(0, max);
    }
}
