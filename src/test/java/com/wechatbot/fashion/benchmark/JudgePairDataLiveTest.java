package com.wechatbot.fashion.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatbot.fashion.ai.fashion.look.FashionResponseFormatter;
import com.wechatbot.fashion.ai.fashion.look.agent.StylistAgent;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CoordinatorOutput;
import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.orchestration.AgentSessionContext;
import com.wechatbot.fashion.graph.FashionGraphRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM-as-judge 评估的成对数据生成（评审证据：生成质量）。
 *
 * <p>对每条真实用户 query 生成两条方案的候选文本：
 * <ul>
 *   <li><b>Path A（完整图管线）</b>：retrieve_memory → planner → rag → stylist → critic∥trend → coordinator；
 *       取 {@link FashionResult#coordinator()} 的最终精炼方案 + 裁决理由。</li>
 *   <li><b>Path B（裸基线）</b>：StylistAgent 直接回答，无 RAG 上下文、无查询分析、无用户画像（空字符串），
 *       代表"大模型直接给穿搭建议"的基线水平。</li>
 * </ul>
 * 输出为 {@code logs/judge_pairs.jsonl}（每行一条 query，含 query / planA / planB / 降级标记 / 时延），
 * 由 {@code scripts/agent_quality_judge.py} 消费并做 win-rate 统计。
 *
 * <p>同一批数据顺带产出端到端图时延（graphElapsedMs，n=59），供 p50/p95 聚合复用。
 *
 * <p>运行：{@code RESUME_JUDGE_DATA=true mvn test -Dtest=JudgePairDataLiveTest}
 * （图以 shadow=true 只读执行，不写业务库；RAG provider 默认 ragflow 与生产同口径）
 *
 * <p>单变量实验模式（改 prompt 后只重生成方案A，方案B 冻结旧文件原文）：
 * <ol>
 *   <li>{@code RESUME_JUDGE_REFRESH_A=true}：加载 {@code RESUME_JUDGE_OUT}（默认 logs/judge_pairs.jsonl）
 *       已有行，仅重跑图生成 planA/aDegraded/graphElapsedMs，planB/bEmpty 保持原值，写出过滤子集到该文件；</li>
 *   <li>{@code RESUME_JUDGE_FILTER=weather|tryon|wardrobe|outfit}：按 query 意图过滤（不设=全部）。</li>
 * </ol>
 */
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.admin.enabled=false",
                "app.persistence.enabled=true",
                "app.persistence.redis.enabled=true",
                "app.fashion.semantic.enabled=false",
                "app.fashion.rag.provider=${RESUME_STUDY_RAG_PROVIDER:ragflow}",
                "app.fashion.rag.diversity.enabled=false",
                "app.fashion.reference.enabled=false",
                "app.fashion.outfit-recommendation.enabled=false",
                "app.ai.usage.daily-token-limit=999999999",
                "app.fashion.graph.enabled=true",
                "app.fashion.graph.shadow=true",
                "app.fashion.graph.tool-loop.enabled=false"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnabledIfEnvironmentVariable(named = "RESUME_JUDGE_DATA", matches = "true")
class JudgePairDataLiveTest {

    /** 与 P5 benchmark 同源：历史真实推荐过的用户输入（先按原文去重，减少重复样本）。 */
    static final String QUERY_SQL =
            "SELECT user_input FROM fashion_conversations WHERE reference_outfit_id <> '' "
                    + "GROUP BY user_input ORDER BY MAX(id) DESC";

    private static final String EVAL_USER = "judge-eval-user";

    @Autowired(required = false)
    private FashionGraphRunner graphRunner;
    @Autowired
    private StylistAgent stylistAgent;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    /** 生产 Formatter：Path A 输出用户可见微信文案（公平比较；内部编号已清理）。 */
    @Autowired
    private FashionResponseFormatter responseFormatter;

    @BeforeEach
    void bind() {
        AgentSessionContext.set(EVAL_USER, "judge-session");
    }

    @AfterEach
    void clear() {
        AgentSessionContext.clear();
    }

    @Test
    void generateJudgePairs() throws Exception {
        if (graphRunner == null) {
            throw new IllegalStateException(
                    "graphRunner 未装配：需 app.fashion.graph.enabled=true（本地 profile 默认开启）");
        }
        if (System.getenv("RESUME_JUDGE_REFRESH_A") != null
                && "true".equalsIgnoreCase(System.getenv("RESUME_JUDGE_REFRESH_A"))) {
            refreshPlanAOnly();
            return;
        }
        List<String> queries = jdbcTemplate.queryForList(QUERY_SQL, String.class);
        String filter = System.getenv("RESUME_JUDGE_FILTER");
        if (filter != null && !filter.isBlank()) {
            queries = queries.stream().filter(q -> intentTag(q).equals(filter)).toList();
            System.out.println("[judge] 过滤意图=" + filter + "，剩 " + queries.size() + " 条");
        }
        queries = dedupeAndClean(queries);
        if (queries.isEmpty()) {
            throw new IllegalStateException("fashion_conversations 无带参考编号的 query（或过滤后为空），无法生成评测对");
        }
        // 可选样本上限（小成本试跑）：RESUME_JUDGE_LIMIT=30
        String limitEnv = System.getenv("RESUME_JUDGE_LIMIT");
        if (limitEnv != null && !limitEnv.isBlank()) {
            int limit = Integer.parseInt(limitEnv.trim());
            if (limit > 0 && queries.size() > limit) {
                queries = queries.subList(0, limit);
                System.out.println("[judge] 限制样本数=" + limit);
            }
        }
        System.out.println("[judge] 共 " + queries.size() + " 条真实 query，开始生成成对方案");

        Path out = outPath();
        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < queries.size(); i++) {
                String input = queries.get(i);
                long t0 = System.currentTimeMillis();
                Optional<FashionResult> maybe = graphRunner.runForResult(
                        new FashionRequest(EVAL_USER, input), "judge-" + i);
                long graphElapsed = System.currentTimeMillis() - t0;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", input);
                row.put("graphElapsedMs", graphElapsed);
                row.put("aDegraded", maybe.isEmpty() || !maybe.get().success() || maybe.get().degraded());

                if (maybe.isPresent() && maybe.get().success() && !maybe.get().degraded()) {
                    row.put("planA", planAText(maybe.get()));
                } else {
                    row.put("planA", "[降级/失败] " + (maybe.map(FashionResult::errorMessage).orElse("图无输出")));
                }

                StylistOutput base = stylistAgent.execute(new FashionRequest(EVAL_USER, input), "", null, "");
                row.put("bEmpty", base == null || base.isEmpty());
                if (base != null && !base.isEmpty()) {
                    StylistOutput.OutfitSuggestion s = base.suggestions().get(0);
                    row.put("planB", outfitText(s));
                } else {
                    row.put("planB", "[基线失败/无输出]");
                }

                w.write(objectMapper.writeValueAsString(row));
                w.newLine();
                if ((i + 1) % 5 == 0 || i == queries.size() - 1) {
                    System.out.println("[judge] 进度 " + (i + 1) + "/" + queries.size()
                            + " 最近之一耗时=" + graphElapsed + "ms");
                }
            }
        }
        System.out.println("[judge] 已写出 " + out.toAbsolutePath());
    }

    /**
     * 单变量模式：加载已有成对文件，仅重跑方案A（图管线），方案B 冻结原值。
     * 用于 prompt 改动后的对比实验——只有 A 侧变化，B 与旧批次完全一致。
     */
    private void refreshPlanAOnly() throws Exception {
        Path out = outPath();
        if (!Files.exists(out)) {
            throw new IllegalStateException("refreshA 模式需要已有 " + out + "，请先跑全量生成");
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                rows.add(objectMapper.readValue(line, LinkedHashMap.class));
            }
        }
        String filter = System.getenv("RESUME_JUDGE_FILTER");
        List<Map<String, Object>> target = rows.stream()
                .filter(r -> filter == null || filter.isBlank()
                        || filter.equals(intentTag(String.valueOf(r.get("query")))))
                .toList();
        System.out.println("[judge-refreshA] 总行 " + rows.size() + "，重生成 " + target.size() + " 条 planA（B 冻结）");

        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (Map<String, Object> row : target) {
                String input = String.valueOf(row.get("query"));
                long t0 = System.currentTimeMillis();
                Optional<FashionResult> maybe = graphRunner.runForResult(
                        new FashionRequest(EVAL_USER, input), "judge-refresh-" + System.nanoTime());
                row.put("graphElapsedMs", System.currentTimeMillis() - t0);
                boolean degraded = maybe.isEmpty() || !maybe.get().success() || maybe.get().degraded();
                row.put("aDegraded", degraded);
                row.put("planA", degraded
                        ? "[降级/失败] " + (maybe.map(FashionResult::errorMessage).orElse("图无输出"))
                        : planAText(maybe.get()));
                w.write(objectMapper.writeValueAsString(row));
                w.newLine();
                System.out.println("[judge-refreshA] 完成 " + input.substring(0, Math.min(24, input.length())) + " ...");
            }
        }
        System.out.println("[judge-refreshA] 已写出 " + out.toAbsolutePath()
                + "（仅含重生成行，planB 保留旧值）");
    }

    /**
     * 去重 + 过滤内部提示词泄漏的历史脏数据，并用轻量归一合并近似重复
     * （如「换一套冬天穿搭」/「帮我推荐一套冬天穿搭」）。
     */
    static List<String> dedupeAndClean(List<String> queries) {
        java.util.LinkedHashMap<String, String> unique = new java.util.LinkedHashMap<>();
        for (String raw : queries) {
            if (raw == null) {
                continue;
            }
            String q = raw.strip();
            if (q.isEmpty() || q.length() > 100) {
                continue;
            }
            if (q.contains("内部") || q.contains("outfit") || q.contains("处理规则")) {
                continue;
            }
            unique.putIfAbsent(normalizeQuery(q), q);
        }
        System.out.println("[judge] 去重清洗后 " + unique.size() + " 条独立 query（原始 " + queries.size() + "）");
        return new java.util.ArrayList<>(unique.values());
    }

    static String normalizeQuery(String q) {
        String s = q.replaceAll("\\s+", "");
        for (String filler : new String[]{"帮我", "推荐", "一套", "再换", "换套", "换", "再", "我要去", "我要",
                "适合", "今天", "给我", "我想", "来"}) {
            s = s.replace(filler, "");
        }
        return s;
    }

    /** 意图粗分（与 scripts/agent_quality_judge.py 的 intent_tag 保持同口径）。 */
    static String intentTag(String q) {
        if (q == null) return "other";
        if (q.matches(".*(试穿|穿一下|上身效果|试试).*")) return "tryon";
        if (q.matches(".*(天气|降温|升温|高温|下雨).*")) return "weather";
        if (q.matches(".*(衣橱|衣柜|我的衣服|衣柜里).*")) return "wardrobe";
        return "outfit";
    }

    private Path outPath() {
        String env = System.getenv("RESUME_JUDGE_OUT");
        return env == null || env.isBlank() ? Path.of("logs/judge_pairs.jsonl") : Path.of(env);
    }

    /** Path A 的用户可见文案：经生产 {@link FashionResponseFormatter}（清理内部编号并排版）。 */
    private String planAText(FashionResult result) {
        String formatted = responseFormatter.format(result);
        return formatted == null || formatted.isBlank() ? planText(result.coordinator()) : formatted;
    }

    /** 完整管线输出：Coordinator 最终精炼方案 + 裁决理由 + 实用建议。 */
    private static String planText(CoordinatorOutput c) {
        if (c == null || c.refinedOutfit() == null) {
            return "[无精炼方案]";
        }
        var o = c.refinedOutfit();
        StringBuilder sb = new StringBuilder();
        sb.append("最终方案：上装=").append(nz(o.top()))
                .append("；下装=").append(nz(o.bottom()))
                .append("；鞋履=").append(nz(o.shoes()))
                .append("；配饰=").append(nz(o.accessories()));
        if (c.finalReasoning() != null && !c.finalReasoning().isBlank()) {
            sb.append("\n理由：").append(c.finalReasoning());
        }
        if (c.practicalTips() != null && !c.practicalTips().isEmpty()) {
            sb.append("\n建议：").append(String.join("；", c.practicalTips()));
        }
        return sb.toString();
    }

    /** 基线输出：Stylist 首选方案文本。 */
    private static String outfitText(StylistOutput.OutfitSuggestion s) {
        StringBuilder sb = new StringBuilder();
        if (s.outfit() != null) {
            sb.append("方案：上装=").append(nz(s.outfit().top()))
                    .append("；下装=").append(nz(s.outfit().bottom()))
                    .append("；鞋履=").append(nz(s.outfit().shoes()))
                    .append("；配饰=").append(nz(s.outfit().accessories()));
        }
        if (s.reasoning() != null && !s.reasoning().isBlank()) {
            sb.append("\n理由：").append(s.reasoning());
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "未给出" : s;
    }
}