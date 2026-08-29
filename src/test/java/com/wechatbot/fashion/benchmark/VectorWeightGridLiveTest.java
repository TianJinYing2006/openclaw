package com.wechatbot.fashion.benchmark;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.ai.fashion.look.rag.RagFlowClient;
import com.wechatbot.fashion.ai.fashion.look.rag.RagFlowProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 零代码 grid：vectorSimilarityWeight 对 top-5 命中率的影响。
 *
 * <p>为排除 QueryAnalyzer LLM 波动（P0 发现单点测量摆动 46.4%~61.4%），
 * 每查询仅 analyze 一次、固定检索词，随后只切换 {@code vector_similarity_weight}
 * （RagFlowClient 每次调用实时读取 properties），观察纯配置变化对
 * top-1 / top-5 / rank 分布的影响。
 *
 * <p>vw = 向量相似度权重（0~1），RAGFlow hybrid score = vw*vector + (1-vw)*term。
 * 现生产配置 0.3（偏关键词）。穿搭属性词吃关键词、场景意图吃向量，最优值靠数据。
 *
 * <p>运行：{@code RESUME_BENCH_LIVE=true RESUME_BENCH_RAG_PROVIDER=ragflow mvn test -Dtest=VectorWeightGridLiveTest}
 * <p>注意：本测试为单轮探测（趋势判断用）；最终选参须以 3 轮 median（greedyStabilityRerun 口径）确认。
 */
@EnabledIfEnvironmentVariable(named = "RESUME_BENCH_LIVE", matches = "true")
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.admin.enabled=false",
                "app.persistence.enabled=true",
                "app.persistence.redis.enabled=false",
                "app.fashion.semantic.enabled=false",
                "app.fashion.rag.provider=${RESUME_BENCH_RAG_PROVIDER:mysql}",
                "app.fashion.rag.diversity.enabled=false",
                "app.fashion.reference.enabled=false",
                "app.fashion.outfit-recommendation.enabled=false",
                "app.ai.usage.daily-token-limit=999999999",
                "app.web-search.provider=bocha",
                "app.weather.provider=uapis"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class VectorWeightGridLiveTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private QueryAnalyzer queryAnalyzer;
    @Autowired(required = false) private RagFlowClient ragFlowClient;
    @Autowired private RagFlowProperties ragProps;

    @Test
    void gridVectorSimilarityWeight() {
        System.out.println("\n===== [P1] vw grid-search：vectorSimilarityWeight 对 top-5 命中影响 =====");
        if (ragFlowClient == null) {
            System.out.println("需 provider=ragflow（RESUME_BENCH_RAG_PROVIDER=ragflow）才能运行。");
            return;
        }
        List<RealQuery> queries = loadQueries();
        if (queries.isEmpty()) {
            System.out.println("fashion_conversations 无带 reference_outfit_id 的记录。");
            return;
        }
        int total = queries.size();

        // 每查询仅分析一次，固定检索词（消除 LLM 波动）
        List<String> searchQuestions = new ArrayList<>();
        List<String> gts = new ArrayList<>();
        int analysisFailures = 0;
        for (RealQuery q : queries) {
            AnalyzedQuery aq;
            try {
                aq = queryAnalyzer.analyze(q.userInput());
            } catch (Exception e) {
                aq = AnalyzedQuery.fallback(q.userInput());
                analysisFailures++;
            }
            searchQuestions.add(buildSearchQuestion(aq));
            gts.add(normalizeOutfitId(q.groundTruth()));
        }
        System.out.println("样本量: " + total + " 条（每查询 LLM 分析 1 次、检索词固定；分析失败回退 " + analysisFailures + " 次）");
        System.out.println("说明: vw 为混合检索向量权重，RAGFlow score = vw*vector + (1-vw)*term；现生产 0.3。单轮探测，趋势判断用。\n");

        int baselineIdx = -1;
        double[] grid = {0.2, 0.3, 0.4, 0.5, 0.6};
        List<int[]> metricsPerVw = new ArrayList<>();   // [top1, top5, rank6-20, 池外]
        List<Map<String, Integer>> ranksPerVw = new ArrayList<>(); // queryIdx -> rank(-1 池外)
        double originalVw = ragProps.getVectorSimilarityWeight();
        try {
            for (double vw : grid) {
                ragProps.setVectorSimilarityWeight(vw);
                int top1 = 0, top5 = 0, rankThen = 0, poolOut = 0;
                Map<String, Integer> rankMap = new LinkedHashMap<>();
                for (int i = 0; i < total; i++) {
                    List<String> poolIds = ragFlowClient.retrieve(searchQuestions.get(i), 20, "", 0.2).stream()
                            .map(c -> normalizeOutfitId(extractDocName(c)))
                            .toList();
                    int idx = poolIds.indexOf(gts.get(i));
                    rankMap.put(queries.get(i).userInput(), idx);
                    if (idx == 0) top1++;
                    if (idx >= 0 && idx < 5) top5++;
                    else if (idx >= 5 && idx < 20) rankThen++;
                    else if (idx < 0) poolOut++;
                }
                if (Math.abs(vw - 0.3) < 1e-9) baselineIdx = metricsPerVw.size();
                metricsPerVw.add(new int[]{top1, top5, rankThen, poolOut});
                ranksPerVw.add(rankMap);
                System.out.printf("vw=%.1f | top-1 %2d/%-2d = %5.1f%% | top-5 %2d/%-2d = %5.1f%% | rank6~20 %2d | 池外 %2d%n",
                        vw, top1, total, pctVal(top1, total), top5, total, pctVal(top5, total), rankThen, poolOut);
            }
        } finally {
            ragProps.setVectorSimilarityWeight(originalVw);
        }

        // vs 基线（0.3）逐条 improved/regressed（按 top-5 命中翻转判定）
        if (baselineIdx >= 0) {
            System.out.println("\n── vs 基线 vw=0.3（top-5 命中翻转明细） ──");
            for (int v = 0; v < grid.length; v++) {
                if (v == baselineIdx) continue;
                int improved = 0, regressed = 0;
                List<String> impr = new ArrayList<>(), regr = new ArrayList<>();
                for (int i = 0; i < total; i++) {
                    int baseRank = ranksPerVw.get(baselineIdx).get(queries.get(i).userInput());
                    int newRank = ranksPerVw.get(v).get(queries.get(i).userInput());
                    boolean baseHit = baseRank >= 0 && baseRank < 5;
                    boolean newHit = newRank >= 0 && newRank < 5;
                    String q = queries.get(i).userInput();
                    if (!baseHit && newHit) { improved++; impr.add(q + "(gt=" + gts.get(i) + " rank " + (baseRank + 1) + "→" + (newRank + 1) + ")"); }
                    else if (baseHit && !newHit) { regressed++; regr.add(q + "(gt=" + gts.get(i) + " rank " + (baseRank + 1) + "→" + (newRank + 1) + ")"); }
                }
                System.out.printf("vw=%.1f: +%d 条救回, -%d 条退步%n", grid[v], improved, regressed);
                impr.forEach(s -> System.out.println("    救回: " + s));
                regr.forEach(s -> System.out.println("    退步: " + s));
            }
        }
    }

    private List<RealQuery> loadQueries() {
        return jdbc.query(
                "SELECT user_input, reference_outfit_id FROM fashion_conversations "
                        + "WHERE reference_outfit_id <> '' ORDER BY id DESC",
                (rs, rowNum) -> new RealQuery(rs.getString("user_input"), rs.getString("reference_outfit_id")));
    }

    private record RealQuery(String userInput, String groundTruth) {}

    private static String normalizeOutfitId(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw.replace("ref_", "").replace("outfit_", "")
                .replace("[", "").replace("]", "").replace(".md", "").strip();
        try {
            return String.format("%03d", Integer.parseInt(cleaned));
        } catch (NumberFormatException e) {
            return cleaned;
        }
    }

    /** 与 RagFlowKnowledgeService.buildSearchQuestion 同构（原文 + 场景/风格/季节）。 */
    private static String buildSearchQuestion(AnalyzedQuery query) {
        StringBuilder sb = new StringBuilder();
        if (query.originalQuery() != null && !query.originalQuery().isBlank()) {
            sb.append(query.originalQuery());
        }
        if (query.params() != null) {
            if (query.params().scene() != null && !query.params().scene().isBlank()) {
                sb.append(" 场景:").append(query.params().scene());
            }
            if (query.params().styleHint() != null && !query.params().styleHint().isBlank()) {
                sb.append(" 风格:").append(query.params().styleHint());
            }
            if (query.params().season() != null && !query.params().season().isBlank()) {
                sb.append(" 季节:").append(query.params().season());
            }
        }
        return sb.toString().trim();
    }

    private static String extractDocName(RagFlowClient.RagFlowChunk c) {
        String n = c.documentName() != null && !c.documentName().isBlank()
                ? c.documentName() : c.documentKeyword();
        return n == null ? "" : n;
    }

    private static double pctVal(int n, int d) {
        return d == 0 ? 0.0 : 100.0 * n / d;
    }
}