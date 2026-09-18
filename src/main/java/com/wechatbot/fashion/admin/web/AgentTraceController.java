package com.wechatbot.fashion.admin.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 链路耗时观测页：直接读取 {@code ai_usage_events}（ToolRegistry 每次工具调用与
 * 模型调用都会落库 duration_ms），展示：
 *
 * <ol>
 *   <li>24h 摘要：MODEL / TOOL 调用量、平均/最大耗时、工具成功率；</li>
 *   <li>工具耗时排行：平均耗时最慢的工具 top-15（查找性能热点）；</li>
 *   <li>最近调用明细：时间、用户（截断）、类型、模型/工具、耗时。</li>
 * </ol>
 *
 * <p>面试演示：穿搭整链的记录中,Critic 与 Trend 的 MODEL 调用会出现在同一时间窗口,
 * 可直观佐证「并行评审」与 21s → 10.5s 的时延优化。</p>
 *
 * <p>仅 admin 启用时生效；未启用持久化时页面提示，不报错。</p>
 */
@Controller
@RequestMapping("/admin/agent-trace")
@ConditionalOnProperty(prefix = "app.admin", name = "enabled", havingValue = "true")
public class AgentTraceController {

    private static final String SINCE_24H = "created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)";

    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public AgentTraceController(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    public record KindSummary(String kind, long count, double avgMs, double maxMs, Double successRate) {
    }

    public record RecentEvent(String time, String user, String kind, String subject, long durationMs,
                              boolean failed, String failureReason) {
    }

    public record ToolRank(String toolName, long count, double avgMs) {
    }

    @GetMapping
    public String view(Model model) {
        model.addAttribute("activePage", "agent-trace");
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            model.addAttribute("error", "链路耗时时序依赖 MySQL（app.persistence.enabled=true），当前不可用。");
            return "admin/agent-trace";
        }
        model.addAttribute("summaries", loadSummaries(jdbc));
        model.addAttribute("toolRanks", loadToolRanks(jdbc));
        model.addAttribute("recent", loadRecent(jdbc, 50));
        return "admin/agent-trace";
    }

    private List<KindSummary> loadSummaries(JdbcTemplate jdbc) {
        List<KindSummary> result = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT kind, COUNT(*) AS cnt, ROUND(AVG(duration_ms), 1) AS avg_ms, ROUND(MAX(duration_ms), 1) AS max_ms
                FROM (
                    SELECT CASE WHEN usage_kind LIKE 'TOOL%%' THEN 'TOOL'
                                WHEN usage_kind = 'MODEL' THEN 'MODEL' ELSE 'OTHER' END AS kind,
                           duration_ms
                    FROM ai_usage_events
                    WHERE %s
                ) t
                GROUP BY kind
                """.formatted(SINCE_24H));
        Long toolOk = jdbc.queryForObject(
                "SELECT SUM(usage_kind = 'TOOL_SUCCESS') FROM ai_usage_events WHERE %s".formatted(SINCE_24H),
                Long.class);
        Long toolBad = jdbc.queryForObject(
                "SELECT SUM(usage_kind = 'TOOL_FAILURE') FROM ai_usage_events WHERE %s".formatted(SINCE_24H),
                Long.class);
        Double successRate = null;
        long toolTotal = (toolOk == null ? 0 : toolOk) + (toolBad == null ? 0 : toolBad);
        if (toolTotal > 0) {
            successRate = 100.0 * (toolOk == null ? 0 : toolOk) / toolTotal;
        }
        for (Map<String, Object> row : rows) {
            String kind = String.valueOf(row.get("kind"));
            result.add(new KindSummary(
                    kind,
                    ((Number) row.get("cnt")).longValue(),
                    ((Number) row.get("avg_ms")).doubleValue(),
                    ((Number) row.get("max_ms")).doubleValue(),
                    "TOOL".equals(kind) ? successRate : null));
        }
        return result;
    }

    private List<ToolRank> loadToolRanks(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT tool_name, COUNT(*) AS cnt, ROUND(AVG(duration_ms), 1) AS avg_ms
                FROM ai_usage_events
                WHERE tool_name <> '' AND %s
                GROUP BY tool_name
                ORDER BY avg_ms DESC
                LIMIT 15
                """.formatted(SINCE_24H),
                (rs, i) -> new ToolRank(
                        rs.getString("tool_name"),
                        rs.getLong("cnt"),
                        rs.getDouble("avg_ms")));
    }

    private List<RecentEvent> loadRecent(JdbcTemplate jdbc, int limit) {
        return jdbc.query("""
                SELECT DATE_FORMAT(created_at, '%%m-%%d %%H:%%i:%%s') AS ts,
                       external_user_id, usage_kind, model, tool_name, duration_ms, failure_reason
                FROM ai_usage_events
                ORDER BY id DESC
                LIMIT ?
                """, (rs, i) -> {
                    String kind = rs.getString("usage_kind");
                    boolean failed = kind.endsWith("FAILURE");
                    String subject = failed || "MODEL".equals(kind)
                            ? rs.getString("model")
                            : rs.getString("tool_name");
                    return new RecentEvent(
                            rs.getString("ts"),
                            shortUser(rs.getString("external_user_id")),
                            kind,
                            subject == null || subject.isBlank() ? kind : subject,
                            rs.getLong("duration_ms"),
                            failed,
                            rs.getString("failure_reason"));
                }, limit);
    }

    private static String shortUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return "-";
        }
        return userId.length() <= 14 ? userId : userId.substring(0, 14) + "…";
    }
}