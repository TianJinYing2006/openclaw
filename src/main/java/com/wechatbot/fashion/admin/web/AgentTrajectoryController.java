package com.wechatbot.fashion.admin.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;

/**
 * Agent 轨迹回放页：读取 {@code agent_runs} / {@code agent_run_steps}（V25），
 * 展示一次穿搭图执行的 run 概览、按节点的耗时/Token 聚合，以及按时间排序的 step 事件流。
 *
 * <p>回答面试常问：「Agent 为什么在这个节点慢/费 Token / 失败后有没有降级」——
 * 数据来自 {@code TrajectoryLifecycleListener}（NODE）与 {@code AgentLlmCaller}（MODEL）。
 */
@Controller
@RequestMapping("/admin/agent-trajectory")
@ConditionalOnProperty(prefix = "app.admin", name = "enabled", havingValue = "true")
public class AgentTrajectoryController {

    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    /** HITL 待确认列表（服务未装配时为空）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.wechatbot.fashion.graph.hitl.ConfirmationService confirmationService;

    public AgentTrajectoryController(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    public record RunRow(String runId, String user, String query, String status, long totalTokens,
                         long durationMs, String graphVersion, String promptVersion, String createdAt) {
    }

    public record NodeRow(String nodeName, long nodeMs, long tokens, long modelCalls, long failedSteps) {
    }

    public record StepRow(int seq, String time, String nodeName, String stepType, String status, String model,
                          String toolName, String inputSummary, String outputSummary, long promptTokens,
                          long completionTokens, long totalTokens, long durationMs, String errorMessage) {
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "agent-trajectory");
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            model.addAttribute("error", "Agent 轨迹依赖 MySQL（app.persistence.enabled=true），当前不可用。");
            return "admin/agent-trajectory";
        }
        model.addAttribute("runs", recentRuns(jdbc, 50));
        model.addAttribute("pendingConfirmations",
                confirmationService == null ? List.of() : confirmationService.pending(50));
        return "admin/agent-trajectory";
    }

    @GetMapping("/{runId}")
    public String detail(@PathVariable String runId, Model model) {
        model.addAttribute("activePage", "agent-trajectory");
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            model.addAttribute("error", "Agent 轨迹依赖 MySQL（app.persistence.enabled=true），当前不可用。");
            return "admin/agent-trajectory-detail";
        }
        RunRow run = run(jdbc, runId);
        if (run == null) {
            model.addAttribute("error", "未找到轨迹 runId=" + runId);
            return "admin/agent-trajectory-detail";
        }
        model.addAttribute("run", run);
        model.addAttribute("nodes", nodeAggregation(jdbc, runId));
        model.addAttribute("steps", steps(jdbc, runId));
        return "admin/agent-trajectory-detail";
    }

    private List<RunRow> recentRuns(JdbcTemplate jdbc, int limit) {
        return jdbc.query("""
                SELECT run_id, external_user_id, query_text, status, total_tokens, duration_ms,
                       graph_version, prompt_version,
                       DATE_FORMAT(created_at, '%%m-%%d %%H:%%i:%%s') AS ts
                FROM agent_runs
                ORDER BY id DESC
                LIMIT ?
                """, (rs, i) -> new RunRow(
                rs.getString("run_id"),
                shortUser(rs.getString("external_user_id")),
                rs.getString("query_text"),
                rs.getString("status"),
                rs.getLong("total_tokens"),
                rs.getLong("duration_ms"),
                rs.getString("graph_version"),
                rs.getString("prompt_version"),
                rs.getString("ts")), limit);
    }

    private RunRow run(JdbcTemplate jdbc, String runId) {
        List<RunRow> rows = jdbc.query("""
                SELECT run_id, external_user_id, query_text, status, total_tokens, duration_ms,
                       graph_version, prompt_version,
                       DATE_FORMAT(created_at, '%%Y-%%m-%%d %%H:%%i:%%s') AS ts
                FROM agent_runs WHERE run_id = ?
                """, (rs, i) -> new RunRow(
                rs.getString("run_id"),
                shortUser(rs.getString("external_user_id")),
                rs.getString("query_text"),
                rs.getString("status"),
                rs.getLong("total_tokens"),
                rs.getLong("duration_ms"),
                rs.getString("graph_version"),
                rs.getString("prompt_version"),
                rs.getString("ts")), runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<NodeRow> nodeAggregation(JdbcTemplate jdbc, String runId) {
        return jdbc.query("""
                SELECT node_name,
                       COALESCE(SUM(CASE WHEN step_type = 'NODE' THEN duration_ms ELSE 0 END), 0) AS node_ms,
                       COALESCE(SUM(CASE WHEN step_type = 'MODEL' THEN total_tokens ELSE 0 END), 0) AS tokens,
                       COALESCE(SUM(CASE WHEN step_type = 'MODEL' THEN 1 ELSE 0 END), 0) AS model_calls,
                       COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_steps
                FROM agent_run_steps
                WHERE run_id = ?
                GROUP BY node_name
                ORDER BY node_ms DESC, tokens DESC
                """, (rs, i) -> new NodeRow(
                rs.getString("node_name"),
                rs.getLong("node_ms"),
                rs.getLong("tokens"),
                rs.getLong("model_calls"),
                rs.getLong("failed_steps")), runId);
    }

    private List<StepRow> steps(JdbcTemplate jdbc, String runId) {
        return jdbc.query("""
                SELECT seq, node_name, step_type, status, model, tool_name, input_summary, output_summary,
                       prompt_tokens, completion_tokens, total_tokens, duration_ms, error_message,
                       DATE_FORMAT(created_at, '%%H:%%i:%%s') AS ts
                FROM agent_run_steps
                WHERE run_id = ?
                ORDER BY id
                """, (rs, i) -> new StepRow(
                rs.getInt("seq"),
                rs.getString("ts"),
                rs.getString("node_name"),
                rs.getString("step_type"),
                rs.getString("status"),
                rs.getString("model"),
                rs.getString("tool_name"),
                rs.getString("input_summary"),
                rs.getString("output_summary"),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getLong("total_tokens"),
                rs.getLong("duration_ms"),
                rs.getString("error_message")), runId);
    }

    private static String shortUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return "-";
        }
        return userId.length() <= 14 ? userId : userId.substring(0, 14) + "…";
    }
}
