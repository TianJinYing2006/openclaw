package com.wechatbot.fashion.golden;

import com.wechatbot.fashion.ai.fashion.look.agent.CoordinatorAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.CriticAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.StylistAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.TrendAgent;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CoordinatorOutput;
import com.wechatbot.fashion.ai.fashion.look.model.CriticOutput;
import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.fashion.look.model.TrendOutput;
import com.wechatbot.fashion.ai.fashion.look.rag.FashionKnowledgeService;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Critic 消融（质量侧，live，默认跳过）。
 *
 * <p>用真实模型在固定查询集上跑 4 组配置，产出对照报告：
 * <ol>
 *   <li>stylist-only（基线）</li>
 *   <li>stylist + critic</li>
 *   <li>stylist + trend</li>
 *   <li>stylist + critic + trend + coordinator（完整）</li>
 * </ol>
 *
 * <p>运行：{@code FASHION_ABLATION_LIVE=true mvn test -Plive -Dtest=FashionAgentAblationLiveTest}
 * （需 MySQL/Redis 与模型密钥）。报告输出 {@code target/golden/critic-ablation-live.md}。
 *
 * <p>确定性指标：完成率、引用 outfit 编号率、方案数、评审分、降级率、墙钟。
 * 约束满足率/幻觉率/人工评分需外接 LLM-as-judge（复用 {@code scripts/agent_quality_judge.py}），本文不伪造。
 */
@EnabledIfEnvironmentVariable(named = "FASHION_ABLATION_LIVE", matches = "true")
@SpringBootTest(properties = {
        "ilink.enabled=false",
        "app.persistence.enabled=true",
        "app.fashion.semantic.enabled=false",
        "app.fashion.reference.enabled=false"
}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FashionAgentAblationLiveTest {

    private static final int CRITIC_PASS_THRESHOLD = 2;

    @Autowired
    private QueryAnalyzer queryAnalyzer;
    @Autowired
    private FashionKnowledgeService knowledgeService;
    @Autowired
    private StylistAgent stylistAgent;
    @Autowired
    private CriticAgent criticAgent;
    @Autowired
    private TrendAgent trendAgent;
    @Autowired
    private CoordinatorAgent coordinatorAgent;

    private record Outcome(String config, String query, int suggestions, boolean hasReferenceOutfitId,
                           int criticScore, boolean degraded, long wallMs, String note) {
    }

    @Test
    void runsFourConfigurationsOnFixedQuerySet() throws Exception {
        List<String> queries = loadQueries();
        List<Outcome> outcomes = new ArrayList<>();
        for (String query : queries) {
            AnalyzedQuery analyzed = queryAnalyzer.analyze(query, "");
            List<com.wechatbot.fashion.ai.fashion.look.model.RetrievedChunk> chunks =
                    knowledgeService.retrieveExcluding(analyzed, java.util.Set.of());
            String rag = knowledgeService.formatContext(chunks);

            outcomes.add(runStylistOnly(query, analyzed, rag));
            outcomes.add(runStylistCritic(query, analyzed, rag));
            outcomes.add(runStylistTrend(query, analyzed, rag));
            outcomes.add(runFull(query, analyzed, rag));
        }
        writeReport(outcomes);
    }

    private Outcome runStylistOnly(String query, AnalyzedQuery analyzed, String rag) {
        long start = System.nanoTime();
        try {
            StylistOutput stylist = stylistAgent.execute(new FashionRequest("ablation", query), rag, analyzed, "");
            return outcome("stylist-only", query, stylist, 0, false, start, "");
        } catch (Exception e) {
            return failure("stylist-only", query, start, e);
        }
    }

    private Outcome runStylistCritic(String query, AnalyzedQuery analyzed, String rag) {
        long start = System.nanoTime();
        try {
            StylistOutput stylist = stylistAgent.execute(new FashionRequest("ablation", query), rag, analyzed, "");
            CriticOutput critic = criticAgent.execute(stylist, analyzed);
            boolean rejected = bestScore(critic) < CRITIC_PASS_THRESHOLD;
            if (rejected) {
                stylist = stylistAgent.execute(new FashionRequest("ablation", query), rag, analyzed, "");
            }
            return outcome("stylist+critic", query, stylist, bestScore(critic), rejected, start,
                    rejected ? "critic 打回并重生成" : "");
        } catch (Exception e) {
            return failure("stylist+critic", query, start, e);
        }
    }

    private Outcome runStylistTrend(String query, AnalyzedQuery analyzed, String rag) {
        long start = System.nanoTime();
        try {
            StylistOutput stylist = stylistAgent.execute(new FashionRequest("ablation", query), rag, analyzed, "");
            trendAgent.execute(stylist, analyzed);
            return outcome("stylist+trend", query, stylist, 0, false, start, "");
        } catch (Exception e) {
            return failure("stylist+trend", query, start, e);
        }
    }

    private Outcome runFull(String query, AnalyzedQuery analyzed, String rag) {
        long start = System.nanoTime();
        try {
            StylistOutput stylist = stylistAgent.execute(new FashionRequest("ablation", query), rag, analyzed, "");
            CriticOutput critic = criticAgent.execute(stylist, analyzed);
            TrendOutput trend = trendAgent.execute(stylist, analyzed);
            CoordinatorOutput coordinator = coordinatorAgent.execute(
                    new FashionRequest("ablation", query), stylist, critic, trend, rag);
            boolean degraded = coordinator == null;
            return outcome("full", query, stylist, bestScore(critic), degraded, start,
                    degraded ? "coordinator 降级" : "");
        } catch (Exception e) {
            return failure("full", query, start, e);
        }
    }

    private static int bestScore(CriticOutput critic) {
        int best = 0;
        if (critic != null && critic.reviews() != null) {
            for (CriticOutput.Critique c : critic.reviews()) {
                best = Math.max(best, c.overallScore());
            }
        }
        return best;
    }

    private static Outcome outcome(String config, String query, StylistOutput stylist, int criticScore,
                                   boolean degraded, long start, String note) {
        int suggestions = stylist == null || stylist.suggestions() == null ? 0 : stylist.suggestions().size();
        boolean hasRef = stylist != null && stylist.suggestions() != null && stylist.suggestions().stream()
                .anyMatch(s -> s.referenceOutfitId() != null && !s.referenceOutfitId().isBlank());
        long wallMs = (System.nanoTime() - start) / 1_000_000;
        return new Outcome(config, query, suggestions, hasRef, criticScore, degraded, wallMs, note);
    }

    private static Outcome failure(String config, String query, long start, Exception e) {
        return new Outcome(config, query, 0, false, 0, true,
                (System.nanoTime() - start) / 1_000_000, "ERROR: " + e.getClass().getSimpleName());
    }

    private static List<String> loadQueries() throws Exception {
        List<String> queries = new ArrayList<>();
        try (InputStream in = FashionAgentAblationLiveTest.class
                .getResourceAsStream("/golden/ablation_queries.txt")) {
            if (in == null) {
                return List.of();
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String q = line.strip();
                if (!q.isEmpty() && !q.startsWith("#")) {
                    queries.add(q);
                }
            }
        }
        return queries;
    }

    private static void writeReport(List<Outcome> outcomes) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Critic 消融（质量侧，live）\n\n");
        sb.append("> 固定查询集 + 真实模型。确定性指标如下；约束满足率/幻觉率/人工评分需 LLM-as-judge，另行补充。\n\n");
        sb.append("| 配置 | query | 方案数 | 含 outfit 编号 | critic 分 | 降级 | 墙钟 | 备注 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Outcome o : outcomes) {
            sb.append("| ").append(o.config())
                    .append(" | ").append(o.query())
                    .append(" | ").append(o.suggestions())
                    .append(" | ").append(o.hasReferenceOutfitId())
                    .append(" | ").append(o.criticScore())
                    .append(" | ").append(o.degraded())
                    .append(" | ").append(o.wallMs()).append("ms")
                    .append(" | ").append(o.note()).append(" |\n");
        }
        Path out = Path.of("target", "golden", "critic-ablation-live.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println(sb);
    }
}
