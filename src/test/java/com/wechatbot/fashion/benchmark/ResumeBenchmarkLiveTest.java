package com.wechatbot.fashion.benchmark;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wechatbot.fashion.ai.fashion.look.agent.AgentCoordinator;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.fashion.look.model.RetrievedChunk;
import com.wechatbot.fashion.ai.fashion.look.rag.FashionKnowledgeService;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.ai.fashion.look.rag.RagFlowClient;
import com.wechatbot.fashion.wardrobe.application.FashionSemanticSearchService;
import com.wechatbot.fashion.wardrobe.config.FashionSemanticProperties;
import com.wechatbot.fashion.wardrobe.domain.SemanticWardrobeMatch;
import com.wechatbot.fashion.wardrobe.domain.WardrobeSearchCriteria;
import com.wechatbot.fashion.wardrobe.persistence.FashionCoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 简历量化数据压测（真实链路）。
 *
 * <p>环境变量 {@code RESUME_BENCH_LIVE=true} 时运行；默认跳过，不干扰日常构建。
 *
 * <p>覆盖三个被质疑的数字：
 * <ol>
 *   <li>检索命中率：用 {@code fashion_conversations} 中带 {@code reference_outfit_id}
 *       （系统实际采纳的参考穿搭编号，即 ground truth）的真实用户查询评测 top-1/top-5 命中率，
 *       替代"50 条自建语料 top-5 100%"的空心数字。</li>
 *   <li>故障窗口降级：Qdrant 故障注入（similaritySearch 抛异常），真实 MySQL 结构化降级路径，
 *       给出故障窗口时长、请求数、成功率、降级时延分布与结果非空率。</li>
 *   <li>端到端管道时延：真实 LLM 调用 {@link AgentCoordinator#process} 多次，
 *       输出 total 的 median/p95/p99 与各 step 时延分解（从日志捕获 Pipeline timings）。</li>
 * </ol>
 *
 * <p>本地环境要求：MySQL（app.persistence.jdbc-url）可达；RAG 检索链路通过环境变量
 * {@code RESUME_BENCH_RAG_PROVIDER} 切换：默认 {@code mysql}（MySQL FTS 降级链路），
 * 设为 {@code ragflow} 时走 RAGFlow 主链路（需 RAGFlow 9380 端口可达）；LLM Key 从
 * application-local.properties 读取。
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
class ResumeBenchmarkLiveTest {

    private static final String REAL_USER = "managed:c277781b-14d7-4bdc-a7f7-4af199e507d3:o9cq8028wfUDZxfAZ1F5G6HAvtt0@im.wechat";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FashionCoreRepository wardrobe;
    @Autowired private FashionKnowledgeService knowledgeService;
    @Autowired(required = false) private RagFlowClient ragFlowClient;
    @Autowired private AgentCoordinator coordinator;
    @Autowired private QueryAnalyzer queryAnalyzer;

    // ─────────────────────────────────────────────────────────────
    // 1. 真实查询日志检索命中率
    // ─────────────────────────────────────────────────────────────

    @Test
    void realQueryLogRetrievalHitRate() {
        System.out.println("\n===== [1] 真实查询日志检索命中率（真实 QueryAnalyzer LLM 分析） =====");
        List<RealQuery> queries = loadRealQueries();
        if (queries.isEmpty()) {
            System.out.println("fashion_conversations 无带 reference_outfit_id 的记录，无法评测。");
            return;
        }

        // ground truth 语料覆盖检查：gt 编号是否存在于 fashion_docs 语料
        int corpusCovered = 0;
        List<String> missingFromCorpus = new ArrayList<>();
        for (RealQuery q : queries) {
            String gt = normalizeOutfitId(q.groundTruth());
            if (docExistsInCorpus(gt)) corpusCovered++;
            else missingFromCorpus.add(gt);
        }

        int top1Hits = 0;
        int top5Hits = 0;
        List<String> failures = new ArrayList<>();
        List<String> rankMisses = new ArrayList<>();
        int analysisFailures = 0;
        for (RealQuery q : queries) {
            AnalyzedQuery aq;
            try {
                aq = queryAnalyzer.analyze(q.userInput());
            } catch (Exception e) {
                analysisFailures++;
                aq = AnalyzedQuery.fallback(q.userInput());
            }
            List<RetrievedChunk> topK = knowledgeService.retrieve(aq);
            List<String> hitIds = topK.stream()
                    .map(c -> normalizeOutfitId(c.entry().id()))
                    .toList();
            String gt = normalizeOutfitId(q.groundTruth());
            boolean top1 = !hitIds.isEmpty() && gt.equals(hitIds.get(0));
            boolean top5 = hitIds.contains(gt);
            if (top1) top1Hits++;
            if (top5) top5Hits++;
            else if (topK.size() > 5 || hitIds.size() >= 5) {
                // gt 在 top-5 之外（>5 候选）→ 排序问题；否则召回缺失
                rankMisses.add("  query=" + q.userInput()
                        + " | gt=" + gt
                        + " | top5=" + hitIds);
            } else {
                failures.add("  query=" + q.userInput()
                        + " | gt=" + gt
                        + " | top5=" + hitIds);
            }
        }

        System.out.println("样本量: " + queries.size() + " 条（真实用户查询，ground truth=系统实际采纳的参考穿搭编号）");
        System.out.println("gt 语料覆盖: " + corpusCovered + "/" + queries.size()
                + "（不在语料中的 gt: " + (missingFromCorpus.isEmpty() ? "无" : missingFromCorpus) + "）");
        System.out.println("QueryAnalyzer LLM 失败回退次数: " + analysisFailures);
        System.out.println("top-1 命中: " + top1Hits + "/" + queries.size()
                + " = " + pct(top1Hits, queries.size()));
        System.out.println("top-5 命中: " + top5Hits + "/" + queries.size()
                + " = " + pct(top5Hits, queries.size()));
        System.out.println("top-5 未命中 —— 候选池 >5 但 gt 不在 top-5（排序问题, " + rankMisses.size() + " 条):");
        rankMisses.forEach(System.out::println);
        System.out.println("top-5 未命中 —— 召回缺失（gt 完全未召回, " + failures.size() + " 条):");
        failures.forEach(System.out::println);

        System.out.println("说明: 检索词由真实 QueryAnalyzer（LLM 分析）构造，与线上链路一致。"
                + "gt 编号已校验在语料中，排除数据覆盖干扰。");
    }

    private boolean docExistsInCorpus(String normalizedGt) {
        java.nio.file.Path p = java.nio.file.Paths.get("data/fashion_docs/outfit_" + normalizedGt + ".md");
        return java.nio.file.Files.exists(p);
    }

    /**
     * 候选池召回率评测：确认"扩大召回候选池"能救回多少 gt。
     *
     * <p>如果 gt 在 top-20 候选池内但不在 top-5 → 排序问题，rerank 有效；
     * 如果 gt 不在候选池 → 召回问题，需要 query 改写/分块调整。
     */
    @Test
    void candidatePoolRecall() {
        System.out.println("\n===== [1b] RAGFlow 候选池召回率（pageSize=20） =====");
        if (ragFlowClient == null) {
            System.out.println("需 provider=ragflow（RESUME_BENCH_RAG_PROVIDER=ragflow）才能运行。");
            return;
        }
        List<RealQuery> queries = loadRealQueries();
        if (queries.isEmpty()) return;

        int inTop5 = 0;
        int inTop20 = 0;
        List<String> rankMisses = new ArrayList<>();   // gt 在 top-20 但不在 top-5
        List<String> recallMisses = new ArrayList<>(); // gt 完全不在 top-20
        for (RealQuery q : queries) {
            AnalyzedQuery aq;
            try {
                aq = queryAnalyzer.analyze(q.userInput());
            } catch (Exception e) {
                aq = AnalyzedQuery.fallback(q.userInput());
            }
            String searchQuestion = buildSearchQuestion(aq);
            List<RagFlowClient.RagFlowChunk> pool = ragFlowClient.retrieve(searchQuestion, 20);
            List<String> poolIds = pool.stream()
                    .map(c -> normalizeOutfitId(extractDocName(c)))
                    .toList();
            String gt = normalizeOutfitId(q.groundTruth());
            int gtIndex = poolIds.indexOf(gt);
            if (gtIndex >= 0 && gtIndex < 5) inTop5++;
            if (gtIndex >= 0) inTop20++;
            if (gtIndex < 0) {
                recallMisses.add("  query=" + q.userInput() + " | gt=" + gt
                        + " | pool=" + poolIds);
            } else if (gtIndex >= 5) {
                rankMisses.add("  query=" + q.userInput() + " | gt=" + gt
                        + " | gt_rank=" + (gtIndex + 1)
                        + " | top5=" + poolIds.subList(0, 5));
            }
        }

        System.out.println("样本量: " + queries.size());
        System.out.println("gt 在 top-5 内: " + inTop5 + "/" + queries.size()
                + " = " + pct(inTop5, queries.size()));
        System.out.println("gt 在 top-20 候选池内: " + inTop20 + "/" + queries.size()
                + " = " + pct(inTop20, queries.size()));
        System.out.println("排序问题（gt 在 top-20 但不在 top-5, rerank 可救, " + rankMisses.size() + " 条):");
        rankMisses.forEach(System.out::println);
        System.out.println("召回缺失（gt 完全不在 top-20, query 改写可救, " + recallMisses.size() + " 条):");
        recallMisses.forEach(System.out::println);
        System.out.println("结论: " + (rankMisses.size() > recallMisses.size()
                ? "排序问题为主 → P2 优先做 rerank"
                : "召回问题为主 → P2 优先做 query 改写"));
    }

    /**
     * Rerank 前后命中率对比：同一批真实查询，分别走 混合检索（baseline）与
     * 混合检索+rerank（qwen3-rerank），对比 top-1/top-5 命中率与逐条变化。
     *
     * <p>rerank 分数量纲与向量相似度不同，baseline 用默认 0.2 阈值，rerank 用 0.0 阈值。
     */
    @Test
    void rerankHitRateComparison() {
        System.out.println("\n===== [1c] Rerank 前后命中率对比 =====");
        if (ragFlowClient == null) {
            System.out.println("需 provider=ragflow（RESUME_BENCH_RAG_PROVIDER=ragflow）才能运行。");
            return;
        }
        String rerankId = System.getenv().getOrDefault(
                "RESUME_BENCH_RERANK_ID", "qwen3-rerank@wechatbot@Tongyi-Qianwen");
        List<RealQuery> queries = loadRealQueries();
        if (queries.isEmpty()) return;

        int baseTop1 = 0, baseTop5 = 0, rkTop1 = 0, rkTop5 = 0;
        int improved = 0, regressed = 0;
        List<String> improvedCases = new ArrayList<>();
        List<String> regressedCases = new ArrayList<>();
        for (RealQuery q : queries) {
            AnalyzedQuery aq;
            try {
                aq = queryAnalyzer.analyze(q.userInput());
            } catch (Exception e) {
                aq = AnalyzedQuery.fallback(q.userInput());
            }
            String searchQuestion = buildSearchQuestion(aq);
            String gt = normalizeOutfitId(q.groundTruth());

            List<String> baseIds = ragFlowClient.retrieve(searchQuestion, 20, "", 0.2).stream()
                    .map(c -> normalizeOutfitId(extractDocName(c)))
                    .toList();
            List<String> rkIds = ragFlowClient.retrieve(searchQuestion, 20, rerankId, 0.0).stream()
                    .map(c -> normalizeOutfitId(extractDocName(c)))
                    .toList();

            boolean baseTop1Hit = !baseIds.isEmpty() && gt.equals(baseIds.get(0));
            boolean baseTop5Hit = baseIds.subList(0, Math.min(5, baseIds.size())).contains(gt);
            boolean rkTop1Hit = !rkIds.isEmpty() && gt.equals(rkIds.get(0));
            boolean rkTop5Hit = rkIds.subList(0, Math.min(5, rkIds.size())).contains(gt);
            if (baseTop1Hit) baseTop1++;
            if (baseTop5Hit) baseTop5++;
            if (rkTop1Hit) rkTop1++;
            if (rkTop5Hit) rkTop5++;

            if (!baseTop5Hit && rkTop5Hit) {
                improved++;
                improvedCases.add("  query=" + q.userInput() + " | gt=" + gt
                        + "\n    baseline top5=" + baseIds + "\n    rerank   top5=" + rkIds);
            } else if (baseTop5Hit && !rkTop5Hit) {
                regressed++;
                regressedCases.add("  query=" + q.userInput() + " | gt=" + gt
                        + "\n    baseline top5=" + baseIds + "\n    rerank   top5=" + rkIds);
            }
        }

        System.out.println("样本量: " + queries.size() + " 条（真实用户查询，ground truth=系统实际采纳编号）");
        System.out.println("rerank 模型: " + rerankId);
        System.out.println("── baseline（混合检索, threshold=0.2）──");
        System.out.println("  top-1: " + baseTop1 + "/" + queries.size() + " = " + pct(baseTop1, queries.size()));
        System.out.println("  top-5: " + baseTop5 + "/" + queries.size() + " = " + pct(baseTop5, queries.size()));
        System.out.println("── baseline + rerank（qwen3-rerank, threshold=0.0）──");
        System.out.println("  top-1: " + rkTop1 + "/" + queries.size() + " = " + pct(rkTop1, queries.size()));
        System.out.println("  top-5: " + rkTop5 + "/" + queries.size() + " = " + pct(rkTop5, queries.size()));
        System.out.println("top-5 净变化: 提升 " + improved + " 条 / 回退 " + regressed + " 条");
        System.out.println("提升样例（baseline 未命中 → rerank 命中, " + improvedCases.size() + " 条):");
        improvedCases.forEach(System.out::println);
        System.out.println("回退样例（baseline 命中 → rerank 未命中, " + regressedCases.size() + " 条):");
        regressedCases.forEach(System.out::println);
    }

    /**
     * Query 改写方案对比：同一批真实查询，QueryAnalyzer 只分析一次（消除 LLM 波动干扰），
     * 用 4 种检索词构造方式分别检索，对比 top-1/top-5：
     * <ul>
     *   <li>V1 现状：复合词 + 英文枚举标签（"场景:WORKPLACE 季节:SUMMER"）</li>
     *   <li>V2 中文映射：枚举映射为中文后拼接（"通勤 商务 夏天"）</li>
     *   <li>V3 子查询拼接：直接用 decomposedQueries（prompt 设计的中英混合检索词）</li>
     *   <li>V4 多路合并：V1 + V3 + 原始查询 三路检索结果按排名轮询去重合并</li>
     * </ul>
     */
    @Test
    void queryRewriteComparison() {
        System.out.println("\n===== [1d] Query 改写方案对比 =====");
        if (ragFlowClient == null) {
            System.out.println("需 provider=ragflow（RESUME_BENCH_RAG_PROVIDER=ragflow）才能运行。");
            return;
        }
        List<RealQuery> queries = loadRealQueries();
        if (queries.isEmpty()) return;

        int[] top1 = new int[4];
        int[] top5 = new int[4];
        for (RealQuery q : queries) {
            AnalyzedQuery aq;
            try {
                aq = queryAnalyzer.analyze(q.userInput());
            } catch (Exception e) {
                aq = AnalyzedQuery.fallback(q.userInput());
            }
            String gt = normalizeOutfitId(q.groundTruth());
            List<List<String>> pools = new ArrayList<>();
            String[] variants = {buildSearchQuestion(aq), buildV2(aq), buildV3(aq)};
            for (String sq : variants) {
                pools.add(ragFlowClient.retrieve(sq, 20, "", 0.2).stream()
                        .map(c -> normalizeOutfitId(extractDocName(c)))
                        .toList());
            }
            // V4: 三路合并（V1/V3 + 原始查询）
            String raw = aq.originalQuery() != null ? aq.originalQuery().strip() : q.userInput();
            pools.add(raw.isBlank() ? List.of()
                    : ragFlowClient.retrieve(raw, 20, "", 0.2).stream()
                            .map(c -> normalizeOutfitId(extractDocName(c)))
                            .toList());
            List<String> merged = mergeRoundRobin(pools, 20);

            List<List<String>> allPools = List.of(pools.get(0), pools.get(1), pools.get(2), merged);
            for (int i = 0; i < 4; i++) {
                List<String> ids = allPools.get(i);
                if (!ids.isEmpty() && gt.equals(ids.get(0))) top1[i]++;
                if (ids.subList(0, Math.min(5, ids.size())).contains(gt)) top5[i]++;
            }
        }

        String[] names = {"V1 现状(英文枚举标签)", "V2 中文映射", "V3 子查询拼接", "V4 多路合并"};
        System.out.println("样本量: " + queries.size() + " 条（QueryAnalyzer 每 query 仅分析一次，消除 LLM 波动）");
        for (int i = 0; i < 4; i++) {
            System.out.printf("  %-20s top-1: %2d/%-2d = %5.1f%%   top-5: %2d/%-2d = %5.1f%%%n",
                    names[i], top1[i], queries.size(), pctVal(top1[i], queries.size()),
                    top5[i], queries.size(), pctVal(top5[i], queries.size()));
        }
    }

    /** V2：场景/季节枚举映射为中文关键词后拼接。 */
    private static String buildV2(AnalyzedQuery q) {
        StringBuilder sb = new StringBuilder();
        if (q.originalQuery() != null && !q.originalQuery().isBlank()) sb.append(q.originalQuery());
        if (q.params() != null) {
            String scene = q.params().scene();
            if (scene != null && !scene.isBlank()) sb.append(" ").append(SCENE_CN.getOrDefault(scene, scene));
            if (q.params().styleHint() != null && !q.params().styleHint().isBlank()) sb.append(" ").append(q.params().styleHint());
            String season = q.params().season();
            if (season != null && !season.isBlank()) sb.append(" ").append(SEASON_CN.getOrDefault(season, season));
        }
        return sb.toString().trim();
    }

    /** V3：直接用 decomposedQueries（prompt 设计的中英混合检索词）。 */
    private static String buildV3(AnalyzedQuery q) {
        if (q.decomposedQueries() != null && !q.decomposedQueries().isEmpty()) {
            return String.join(" ", q.decomposedQueries());
        }
        return buildV2(q);
    }

    /** 多路结果按排名轮询去重合并。 */
    private static List<String> mergeRoundRobin(List<List<String>> pools, int limit) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        int max = pools.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < max; i++) {
            for (List<String> pool : pools) {
                if (i < pool.size()) seen.add(pool.get(i));
            }
        }
        return seen.stream().limit(limit).toList();
    }

    private static double pctVal(int n, int total) {
        return total == 0 ? 0 : n * 100.0 / total;
    }

    private static final Map<String, String> SCENE_CN = Map.of(
            "WORKPLACE", "通勤", "COMMUTE", "通勤", "FORMAL_EVENT", "正式场合",
            "SCHOOL", "校园", "TRAVEL", "旅行", "OUTDOOR", "户外", "DAILY", "日常");
    private static final Map<String, String> SEASON_CN = Map.of(
            "SPRING", "春天", "SUMMER", "夏天", "AUTUMN", "秋天", "WINTER", "冬天");

    /**
     * 贪婪采样 + 3 轮重测：验证 QueryAnalyzer 改 temperature=0 后，同一批真实查询的
     * 检索命中率是否可复现（消除此前 ±37pt 的 LLM 随机性波动）。
     *
     * <p>每轮用 {@code analyzeUncached} 真实调用 LLM（绕过缓存），否则三轮必然相同、
     * 无法验证确定性。报告每轮 top-1/top-5 及 3 轮 median 与波动范围。
     */
    @Test
    void greedyStabilityRerun() {
        System.out.println("\n===== [1e] QueryAnalyzer 贪婪采样 3 轮重测（可复现性验证） =====");
        if (ragFlowClient == null) {
            System.out.println("需 provider=ragflow（RESUME_BENCH_RAG_PROVIDER=ragflow）才能运行。");
            return;
        }
        List<RealQuery> queries = loadRealQueries();
        if (queries.isEmpty()) return;

        int rounds = 3;
        int[] top1 = new int[rounds];
        int[] top5 = new int[rounds];
        int[] top20 = new int[rounds];
        for (int round = 0; round < rounds; round++) {
            for (RealQuery q : queries) {
                AnalyzedQuery aq;
                try {
                    aq = queryAnalyzer.analyzeUncached(q.userInput(), "");
                } catch (Exception e) {
                    aq = AnalyzedQuery.fallback(q.userInput());
                }
                String searchQuestion = buildSearchQuestion(aq);
                String gt = normalizeOutfitId(q.groundTruth());
                List<String> ids = ragFlowClient.retrieve(searchQuestion, 20, "", 0.2).stream()
                        .map(c -> normalizeOutfitId(extractDocName(c)))
                        .toList();
                int limit5 = Math.min(5, ids.size());
                if (!ids.isEmpty() && gt.equals(ids.get(0))) top1[round]++;
                if (ids.subList(0, limit5).contains(gt)) top5[round]++;
                if (ids.contains(gt)) top20[round]++;
            }
            System.out.printf("  第 %d 轮: top-1 %2d/%-2d = %5.1f%%   top-5 %2d/%-2d = %5.1f%%   top-20 %2d/%-2d = %5.1f%%%n",
                    round + 1, top1[round], queries.size(), pctVal(top1[round], queries.size()),
                    top5[round], queries.size(), pctVal(top5[round], queries.size()),
                    top20[round], queries.size(), pctVal(top20[round], queries.size()));
        }
        int t1Min = java.util.Arrays.stream(top1).min().orElse(0);
        int t1Max = java.util.Arrays.stream(top1).max().orElse(0);
        int t5Min = java.util.Arrays.stream(top5).min().orElse(0);
        int t5Max = java.util.Arrays.stream(top5).max().orElse(0);
        int t20Min = java.util.Arrays.stream(top20).min().orElse(0);
        int t20Max = java.util.Arrays.stream(top20).max().orElse(0);
        System.out.println("样本量: " + queries.size() + " 条（每轮独立真实 LLM 分析，绕过缓存）");
        System.out.println("top-1 波动: " + t1Min + "~" + t1Max + "（" + pctVal(t1Min, queries.size())
                + "%~" + pctVal(t1Max, queries.size()) + "%），3 轮 median=" + medianOf(top1) + "/" + queries.size());
        System.out.println("top-5 波动: " + t5Min + "~" + t5Max + "（" + pctVal(t5Min, queries.size())
                + "%~" + pctVal(t5Max, queries.size()) + "%），3 轮 median=" + medianOf(top5) + "/" + queries.size());
        System.out.println("top-20 波动: " + t20Min + "~" + t20Max + "（" + pctVal(t20Min, queries.size())
                + "%~" + pctVal(t20Max, queries.size()) + "%），3 轮 median=" + medianOf(top20) + "/" + queries.size());
        System.out.println("结论: 波动范围越小，数字越可复现，越能扛住'现场再跑一遍'的追问。");
    }

    private static int medianOf(int[] sorted) {
        int[] copy = sorted.clone();
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static String extractDocName(RagFlowClient.RagFlowChunk c) {
        String n = c.documentName() != null && !c.documentName().isBlank()
                ? c.documentName() : c.documentKeyword();
        return n == null ? "" : n;
    }

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

    private List<RealQuery> loadRealQueries() {
        return jdbc.query(
                "SELECT user_input, reference_outfit_id FROM fashion_conversations "
                        + "WHERE reference_outfit_id <> '' ORDER BY id DESC",
                (rs, rowNum) -> new RealQuery(rs.getString("user_input"), rs.getString("reference_outfit_id")));
    }

    private record RealQuery(String userInput, String groundTruth) {}

    /** 统一 outfit 编号：ref_002 / 002 / [outfit_002] / 2 / 043.md → 002（三位零填充）。 */
    private static String normalizeOutfitId(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw.replace("ref_", "")
                .replace("outfit_", "")
                .replace("[", "")
                .replace("]", "")
                .replace(".md", "")
                .strip();
        try {
            return String.format("%03d", Integer.parseInt(cleaned));
        } catch (NumberFormatException e) {
            return cleaned;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. Qdrant 故障窗口降级成功率 + 降级时延
    // ─────────────────────────────────────────────────────────────

    @Test
    void qdrantFailureDegradationWindow() {
        System.out.println("\n===== [2] Qdrant 故障窗口降级成功率 =====");
        VectorStore brokenVectors = mock(VectorStore.class);
        when(brokenVectors.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("qdrant unavailable (simulated outage)"));

        FashionSemanticSearchService service = new FashionSemanticSearchService(
                wardrobe, brokenVectors, new FashionSemanticProperties());

        List<String> queries = List.of(
                "浅色上衣", "夏天的短袖", "面试穿的正式衣服", "去海边穿什么", "灰色卫衣");
        WardrobeSearchCriteria none = WardrobeSearchCriteria.from(
                null, null, List.of(), null, null, List.of(), List.of(), null);

        Instant windowStart = Instant.now();
        int total = 0;
        int succeeded = 0;
        int nonEmpty = 0;
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String query = queries.get(i % queries.size());
            long start = System.nanoTime();
            try {
                List<SemanticWardrobeMatch> result = service.search(REAL_USER, query, none, 5);
                total++;
                succeeded++;
                if (!result.isEmpty()) nonEmpty++;
            } catch (Exception e) {
                total++;
                System.out.println("  FAILED: query=" + query + " err=" + e.getMessage());
            } finally {
                latencies.add(Duration.ofNanos(System.nanoTime() - start).toMillis());
            }
        }
        Instant windowEnd = Instant.now();

        List<Long> sorted = latencies.stream().sorted().toList();
        System.out.println("故障窗口时长: " + Duration.between(windowStart, windowEnd).getSeconds() + "s");
        System.out.println("请求量: " + total + "（5 条语义查询 x 4 轮）");
        System.out.println("成功率: " + succeeded + "/" + total + " = " + pct(succeeded, total));
        System.out.println("降级后结果非空率: " + nonEmpty + "/" + total + " = " + pct(nonEmpty, total));
        System.out.println("降级响应时延: median=" + median(sorted) + "ms"
                + ", p95=" + percentile(sorted, 0.95) + "ms"
                + ", p99=" + percentile(sorted, 0.99) + "ms"
                + ", min=" + sorted.get(0) + "ms, max=" + sorted.get(sorted.size() - 1) + "ms");
        System.out.println("说明: Qdrant 故障通过 VectorStore.similaritySearch 抛异常注入，"
                + "降级路径为真实 MySQL 结构化过滤（FashionSemanticSearchService.structuredFallback）。");
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 端到端管道时延分布（真实 LLM）
    // ─────────────────────────────────────────────────────────────

    @Test
    void pipelineLatencyDistribution() {
        System.out.println("\n===== [3] 端到端管道时延分布（真实 LLM 链路） =====");
        String benchUser = "resume-bench-" + System.currentTimeMillis();

        List<String> queries = List.of(
                "帮我推荐一套适合上班的通勤穿搭",      // 简单请求（formality 低）
                "推荐一套适合去海边度假的穿搭，天气很热", // 可能复杂
                "参加婚礼怎么穿，我是男生");          // 可能复杂
        int repeats = 2;

        Logger logback = (Logger) LoggerFactory.getLogger(AgentCoordinator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logback.addAppender(appender);

        List<Long> totals = new ArrayList<>();
        Map<String, List<Long>> stepTimings = new HashMap<>();
        int totalRequests = 0;
        int successful = 0;
        try {
            for (int r = 0; r < repeats; r++) {
                for (String q : queries) {
                    appender.list.clear();
                    long start = System.nanoTime();
                    coordinator.process(new FashionRequest(benchUser, q));
                    long totalMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
                    totals.add(totalMs);
                    totalRequests++;
                    successful++;
                    Map<String, Long> steps = parsePipelineTimings(appender);
                    steps.forEach((k, v) -> stepTimings.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
                    System.out.println("  request=" + q
                            + " | total=" + totalMs + "ms"
                            + " | " + steps);
                }
            }
        } finally {
            logback.detachAppender(appender);
            jdbc.update("DELETE FROM fashion_conversations WHERE user_id = ?", benchUser);
        }

        List<Long> sorted = totals.stream().sorted().toList();
        System.out.println("请求量: " + totalRequests + "（" + queries.size() + " 类请求 x " + repeats + " 轮）");
        System.out.println("成功率: " + successful + "/" + totalRequests + " = " + pct(successful, totalRequests));
        System.out.println("端到端 total: median=" + median(sorted) + "ms"
                + ", p95=" + percentile(sorted, 0.95) + "ms"
                + ", p99=" + percentile(sorted, 0.99) + "ms"
                + ", min=" + sorted.get(0) + "ms, max=" + sorted.get(sorted.size() - 1) + "ms");
        System.out.println("step 时延分解 (median):");
        stepTimings.forEach((step, list) -> {
            List<Long> s = list.stream().sorted().toList();
            System.out.println("  " + step + ": median=" + median(s) + "ms, n=" + s.size());
        });
        System.out.println("说明: 真实 LLM 调用（app.ai.fashion-model / app.ai.model），"
                + "RAG 检索走 MySQL FTS（provider=mysql）。简单请求跳过 Critic/Trend/Coordinator，"
                + "复杂请求走 5 次 LLM 调用。step 时延从 AgentCoordinator 日志 Pipeline timings 捕获。");
    }

    private static final Pattern TIMING_PATTERN =
            Pattern.compile("(\\w+)=(\\d+)ms");

    private static Map<String, Long> parsePipelineTimings(ListAppender<ILoggingEvent> appender) {
        Map<String, Long> steps = new HashMap<>();
        for (ILoggingEvent event : appender.list) {
            if (event.getFormattedMessage().contains("Pipeline timings:")) {
                Matcher m = TIMING_PATTERN.matcher(event.getFormattedMessage());
                while (m.find()) {
                    steps.put(m.group(1), Long.parseLong(m.group(2)));
                }
            }
        }
        return steps;
    }

    private static String pct(int num, int den) {
        return String.format("%.1f%%", den == 0 ? 0.0 : num * 100.0 / den);
    }

    private static long median(List<Long> sorted) {
        if (sorted.isEmpty()) return 0;
        int n = sorted.size();
        return n % 2 == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    // ─────────────────────────────────────────────────────────────
    // 1f. 语义命中率（验证 ground truth 噪声：top-5 里有同样合适的 outfit 吗）
    // ─────────────────────────────────────────────────────────────

    @Test
    void semanticHitRateComparison() {
        System.out.println("\n===== [1f] 语义命中率（验证 ground truth 噪声） =====");
        if (ragFlowClient == null) {
            System.out.println("RAGFlow client 不可用（需 RESUME_BENCH_RAG_PROVIDER=ragflow）");
            return;
        }
        Map<String, OutfitTags> tagsMap = loadOutfitTags();
        System.out.println("加载了 " + tagsMap.size() + " 个 outfit 的结构化标签");
        if (tagsMap.isEmpty()) {
            System.out.println("无法加载 outfit 标签，跳过。");
            return;
        }

        List<RealQuery> queries = loadRealQueries();
        int strictTop5 = 0, strictTop20 = 0;
        int sceneTop5 = 0, sceneTop20 = 0;
        int sceneStyleTop5 = 0, sceneStyleTop20 = 0;
        int tagsNotFound = 0;

        for (RealQuery q : queries) {
            AnalyzedQuery aq;
            try {
                aq = queryAnalyzer.analyze(q.userInput(), "");
            } catch (Exception e) {
                aq = AnalyzedQuery.fallback(q.userInput());
            }
            String searchQuestion = buildSearchQuestion(aq);
            String gt = normalizeOutfitId(q.groundTruth());

            OutfitTags gtTags = tagsMap.get(gt);
            if (gtTags == null) {
                tagsNotFound++;
            }

            List<String> ids = ragFlowClient.retrieve(searchQuestion, 20, "", 0.2).stream()
                    .map(c -> normalizeOutfitId(extractDocName(c)))
                    .toList();
            int limit5 = Math.min(5, ids.size());

            // 严格精确匹配
            if (ids.subList(0, limit5).contains(gt)) strictTop5++;
            if (ids.contains(gt)) strictTop20++;

            if (gtTags == null) continue;

            // 语义匹配：top-5 / top-20 里有 outfit 和 gt 共享 ≥1 场合 / ≥1 场合+≥1 风格
            for (int i = 0; i < ids.size(); i++) {
                OutfitTags ot = tagsMap.get(ids.get(i));
                if (ot == null) continue;
                boolean sceneMatch = hasIntersection(gtTags.scenes(), ot.scenes());
                boolean styleMatch = hasIntersection(gtTags.styles(), ot.styles());
                if (sceneMatch) {
                    if (i < limit5) sceneTop5++;
                    sceneTop20++;
                    break;
                }
            }
            for (int i = 0; i < ids.size(); i++) {
                OutfitTags ot = tagsMap.get(ids.get(i));
                if (ot == null) continue;
                boolean sceneMatch = hasIntersection(gtTags.scenes(), ot.scenes());
                boolean styleMatch = hasIntersection(gtTags.styles(), ot.styles());
                if (sceneMatch && styleMatch) {
                    if (i < limit5) sceneStyleTop5++;
                    sceneStyleTop20++;
                    break;
                }
            }
        }

        int n = queries.size();
        System.out.println("样本量: " + n + " 条（" + tagsNotFound + " 条 ground truth 无标签，仅算 strict）");
        System.out.println();
        System.out.println("指标                              top-5          top-20");
        System.out.println("──────────────────────────────────────────────────────────");
        System.out.printf("严格精确匹配 (reference_outfit_id)  %2d/%-2d = %5.1f%%   %2d/%-2d = %5.1f%%%n",
                strictTop5, n, pctVal(strictTop5, n), strictTop20, n, pctVal(strictTop20, n));
        System.out.printf("语义匹配 (>=1 同场合)              %2d/%-2d = %5.1f%%   %2d/%-2d = %5.1f%%%n",
                sceneTop5, n, pctVal(sceneTop5, n), sceneTop20, n, pctVal(sceneTop20, n));
        System.out.printf("语义匹配 (>=1 同场合+>=1 同风格)    %2d/%-2d = %5.1f%%   %2d/%-2d = %5.1f%%%n",
                sceneStyleTop5, n, pctVal(sceneStyleTop5, n), sceneStyleTop20, n, pctVal(sceneStyleTop20, n));
        System.out.println();
        System.out.println("解读: 语义匹配率 > 严格匹配率 -> ground truth 是多样性采样产物，");
        System.out.println("      top-5 里有同样合适的 outfit，只是不是 reference_outfit_id 那套。");
        System.out.println("      差值越大 = ground truth 噪声越大 = 严格匹配率越失真。");
    }

    private record OutfitTags(List<String> scenes, List<String> seasons,
                               List<String> styles, double formality) {}

    private static boolean hasIntersection(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        for (String s : a) {
            if (b.contains(s)) return true;
        }
        return false;
    }

    private static Map<String, OutfitTags> loadOutfitTags() {
        Map<String, OutfitTags> tags = new HashMap<>();
        java.nio.file.Path docsDir = java.nio.file.Paths.get("data/fashion_docs");
        if (!java.nio.file.Files.isDirectory(docsDir)) {
            return tags;
        }
        Pattern filePattern = Pattern.compile("outfit_(\\d+)\\.md");
        Pattern overviewPattern = Pattern.compile(
                "## 整套搭配概览\\s*\\n(.*?)(?=\\n## |\\Z)", Pattern.DOTALL);
        try (var stream = java.nio.file.Files.list(docsDir)) {
            stream.filter(p -> filePattern.matcher(p.getFileName().toString()).matches())
                    .forEach(p -> {
                        Matcher m = filePattern.matcher(p.getFileName().toString());
                        if (!m.matches()) return;
                        String outfitId = String.format("%03d", Integer.parseInt(m.group(1)));
                        try {
                            String md = java.nio.file.Files.readString(p);
                            Matcher om = overviewPattern.matcher(md);
                            if (om.find()) {
                                tags.put(outfitId, parseOutfitTags(om.group(1).strip()));
                            }
                        } catch (java.io.IOException e) {
                            // skip
                        }
                    });
        } catch (java.io.IOException e) {
            // skip
        }
        return tags;
    }

    private static OutfitTags parseOutfitTags(String overview) {
        List<String> styles = extractTagList(overview, "整体风格[:：]\\s*(.+?)\\s*(?:\\n|$)");
        List<String> seasons = extractTagList(overview, "适合季节[:：]\\s*(.+?)\\s*(?:\\n|$)");
        List<String> scenes = extractTagList(overview, "适合场合[:：]\\s*(.+?)\\s*(?:\\n|$)");
        double formality = 0.0;
        Matcher m = Pattern.compile("整体正式度[:：]\\s*([\\d.]+)/5").matcher(overview);
        if (m.find()) {
            try { formality = Double.parseDouble(m.group(1)); } catch (NumberFormatException e) { }
        }
        return new OutfitTags(scenes, seasons, styles, formality);
    }

    private static List<String> extractTagList(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        if (!m.find()) return List.of();
        String raw = m.group(1).strip();
        if (raw.isEmpty()) return List.of();
        return List.of(raw.split("[/、，,\\s]+"));
    }
}
