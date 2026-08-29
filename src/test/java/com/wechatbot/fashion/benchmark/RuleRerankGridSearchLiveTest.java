package com.wechatbot.fashion.benchmark;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.ai.fashion.look.rag.RagFlowClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 结构化规则重排权重网格搜索。
 *
 * <p>对真实用户查询（带 gt）先调用 RAGFlow 拿到 outfit 级候选池（含 semantic score），
 * 然后在内存中扫描规则权重组合，找出 top-5 命中率最高的配置。
 *
 * <p>运行：{@code RESUME_BENCH_LIVE=true RESUME_BENCH_RAG_PROVIDER=ragflow mvn test -Dtest=RuleRerankGridSearchLiveTest}
 * <p>结果重定向：{@code > logs/p5_rule_rerank_grid.log 2>&1}
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
class RuleRerankGridSearchLiveTest {

    private static final String FASHION_DOCS_DIR = "data/fashion_docs";
    private static final Pattern OVERVIEW_PATTERN = Pattern.compile(
            "## 整套搭配概览\\s*\\n(.*?)(?=\\n## |\\Z)", Pattern.DOTALL);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private QueryAnalyzer queryAnalyzer;
    @Autowired(required = false) private RagFlowClient ragFlowClient;

    private record RealQuery(String userInput, String groundTruth) {}
    private record OutfitTags(List<String> scenes, List<String> seasons, List<String> styles, double formality) {}
    private record Candidate(String outfitId, double semanticScore) {}
    private record QueryPool(RealQuery query, AnalyzedQuery analyzed, List<Candidate> candidates) {}
    private record Weights(double semanticWeight,
                           double sceneWeight, double seasonWeight,
                           double styleWeight, double formalityWeight,
                           double ruleScoreCap) {}

    @Test
    void gridSearchRuleRerankWeights() throws IOException {
        System.out.println("\n===== [P5] 结构化规则重排权重网格搜索 =====");
        if (ragFlowClient == null) {
            System.out.println("需 provider=ragflow（RESUME_BENCH_RAG_PROVIDER=ragflow）才能运行。");
            return;
        }

        Map<String, OutfitTags> outfitTags = loadOutfitTags();
        List<RealQuery> queries = loadQueries();
        if (queries.isEmpty()) {
            System.out.println("fashion_conversations 无带 reference_outfit_id 的记录，无法评测。");
            return;
        }

        // 1. 为每个 query 调多路 RAGFlow + RRF 融合，收集候选池（含 semantic score）
        List<QueryPool> pools = new ArrayList<>();
        for (RealQuery q : queries) {
            AnalyzedQuery aq;
            try {
                aq = queryAnalyzer.analyze(q.userInput());
            } catch (Exception e) {
                aq = AnalyzedQuery.fallback(q.userInput());
            }
            // 多路 RRF：与生产 RagFlowKnowledgeService.retrieveOutfitPool 同口径
            List<Candidate> candidates;
            try {
                candidates = retrieveMultiRouteRrf(aq);
            } catch (Exception e) {
                System.out.println("RETRIEVE_ERROR | query=" + truncate(q.userInput()) + " | err=" + e.getMessage());
                continue;
            }
            pools.add(new QueryPool(q, aq, candidates));
        }

        System.out.println("成功收集候选池: " + pools.size() + "/" + queries.size() + " 条 query");
        System.out.println(" outfit 标签库: " + outfitTags.size() + " 套");

        // 2. 扫描权重组合
        List<Weights> weightList = buildWeightGrid();
        System.out.println("权重组合数: " + weightList.size());

        Weights best = null;
        double bestTop5 = -1;
        int bestTop1 = -1;
        Map<String, Double> results = new LinkedHashMap<>();

        for (Weights w : weightList) {
            int top1 = 0, top5 = 0;
            for (QueryPool pool : pools) {
                List<Candidate> reranked = rerank(pool.candidates, pool.analyzed, outfitTags, w);
                int rank = findRank(reranked, pool.query.groundTruth());
                if (rank == 0) { top1++; top5++; }
                else if (rank >= 1 && rank <= 4) top5++;
            }
            double top5Pct = 100.0 * top5 / pools.size();
            if (top5Pct > bestTop5 || (top5Pct == bestTop5 && top1 > bestTop1)) {
                bestTop5 = top5Pct;
                bestTop1 = top1;
                best = w;
            }
            results.put(key(w), top5Pct);
        }

        // 3. 输出最优配置
        System.out.println("\n===== 最优规则重排配置 =====");
        System.out.println("semanticWeight=" + best.semanticWeight
                + " scene=" + best.sceneWeight
                + " season=" + best.seasonWeight
                + " style=" + best.styleWeight
                + " formality=" + best.formalityWeight
                + " cap=" + best.ruleScoreCap);
        System.out.println("top-1: " + bestTop1 + "/" + pools.size() + " = " + pct(bestTop1, pools.size()));
        System.out.println("top-5: " + (int) Math.round(bestTop5 * pools.size() / 100.0) + "/" + pools.size()
                + " = " + String.format(Locale.US, "%.2f%%", bestTop5));

        // 4. 与无规则重排 baseline 对比
        Weights baselineWeights = new Weights(1.0, 0, 0, 0, 0, 0);
        int baselineTop1 = 0, baselineTop5 = 0;
        for (QueryPool pool : pools) {
            List<Candidate> reranked = rerank(pool.candidates, pool.analyzed, outfitTags, baselineWeights);
            int rank = findRank(reranked, pool.query.groundTruth());
            if (rank == 0) { baselineTop1++; baselineTop5++; }
            else if (rank >= 1 && rank <= 4) baselineTop5++;
        }
        System.out.println("\n===== 无规则重排 baseline（semantic only） =====");
        System.out.println("top-1: " + baselineTop1 + "/" + pools.size() + " = " + pct(baselineTop1, pools.size()));
        System.out.println("top-5: " + baselineTop5 + "/" + pools.size() + " = " + pct(baselineTop5, pools.size()));

        int bestTop5Count = (int) Math.round(bestTop5 * pools.size() / 100.0);
        System.out.println("\n===== 提升 =====");
        System.out.println("top-5 绝对提升: " + (bestTop5Count - baselineTop5) + " 条 ("
                + String.format(Locale.US, "+%.2fpp", bestTop5 - 100.0 * baselineTop5 / pools.size()) + ")");

        // 5. 输出 top-10 权重配置
        System.out.println("\n===== top-10 配置（按 top-5 降序） =====");
        results.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> System.out.println(String.format(Locale.US, "%.2f%% | %s", e.getValue(), e.getKey())));

        // 6. 保存原始候选池到文件，供后续纯离线分析
        savePools(pools, Paths.get("logs/p5_rule_rerank_pools.tsv"));
    }

    private List<Weights> buildWeightGrid() {
        double[] semanticWeights = {0.70, 0.75, 0.80, 0.85, 0.90};
        double[] sceneWeights = {0.40, 0.50, 0.60};
        double[] seasonWeights = {0.10, 0.15, 0.20};
        double[] styleWeights = {0.10, 0.15, 0.20};
        double[] formalityWeights = {0.05, 0.10, 0.15};
        double[] ruleScoreCaps = {0.50, 0.65, 0.80};

        List<Weights> list = new ArrayList<>();
        for (double sw : semanticWeights) {
            for (double scene : sceneWeights) {
                for (double season : seasonWeights) {
                    for (double style : styleWeights) {
                        for (double formality : formalityWeights) {
                            for (double cap : ruleScoreCaps) {
                                list.add(new Weights(sw, scene, season, style, formality, cap));
                            }
                        }
                    }
                }
            }
        }
        return list;
    }

    private List<Candidate> rerank(List<Candidate> candidates, AnalyzedQuery aq,
                                   Map<String, OutfitTags> outfitTags, Weights w) {
        AnalyzedQuery.QueryParams params = aq.params();
        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            OutfitTags tags = outfitTags.get(c.outfitId());
            double ruleScore = (tags == null) ? 0.0 : computeRuleScore(params, tags, w);
            double finalScore = w.semanticWeight() * c.semanticScore() + (1.0 - w.semanticWeight()) * ruleScore;
            scored.add(new ScoredCandidate(c, finalScore));
        }
        scored.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
        return scored.stream().map(s -> s.candidate).toList();
    }

    private record ScoredCandidate(Candidate candidate, double finalScore) {}

    private double computeRuleScore(AnalyzedQuery.QueryParams params, OutfitTags tags, Weights w) {
        double score = 0.0;
        if (params != null) {
            if (params.scene() != null && !params.scene().isBlank()) {
                if (matchesScene(params.scene(), tags.scenes())) score += w.sceneWeight();
            }
            if (params.season() != null && !params.season().isBlank()) {
                if (matchesSeason(params.season(), tags.seasons())) score += w.seasonWeight();
            }
            if (params.styleHint() != null && !params.styleHint().isBlank()) {
                if (matchesStyle(params.styleHint(), tags.styles())) score += w.styleWeight();
            }
            if (params.formality() > 0 && tags.formality() > 0) {
                if (Math.abs(params.formality() - tags.formality()) <= 1.0) score += w.formalityWeight();
            }
        }
        return Math.min(w.ruleScoreCap(), score);
    }

    private static boolean matchesScene(String queryScene, List<String> outfitScenes) {
        Set<String> normalized = outfitScenes.stream()
                .map(RuleRerankGridSearchLiveTest::normalizeSceneCn)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
        return normalized.contains(queryScene.toUpperCase());
    }

    private static boolean matchesSeason(String querySeason, List<String> outfitSeasons) {
        Set<String> normalized = outfitSeasons.stream()
                .map(RuleRerankGridSearchLiveTest::normalizeSeasonCn)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
        return normalized.contains(querySeason.toUpperCase());
    }

    private static boolean matchesStyle(String queryStyleHint, List<String> outfitStyles) {
        if (queryStyleHint == null || queryStyleHint.isBlank()) return false;
        Set<String> queryStyles = Set.of(queryStyleHint.split("[/、，,\\s]+"));
        return outfitStyles.stream().anyMatch(queryStyles::contains);
    }

    private static String normalizeSceneCn(String scene) {
        return switch (scene.trim()) {
            case "通勤", "上班", "工作", "办公", "开会" -> "WORKPLACE";
            case "正式场合", "婚礼", "结婚", "晚宴", "宴会" -> "FORMAL_EVENT";
            case "校园", "上学", "学生" -> "SCHOOL";
            case "旅行", "旅游", "出差" -> "TRAVEL";
            case "户外", "运动", "健身", "跑步", "爬山", "海边", "海滩", "度假" -> "OUTDOOR";
            case "日常", "休闲", "逛街" -> "DAILY";
            default -> scene.trim().toUpperCase();
        };
    }

    private static String normalizeSeasonCn(String season) {
        return switch (season.trim()) {
            case "春天", "春季" -> "SPRING";
            case "夏天", "夏季" -> "SUMMER";
            case "秋天", "秋季" -> "AUTUMN";
            case "冬天", "冬季" -> "WINTER";
            default -> season.trim().toUpperCase();
        };
    }

    private List<Candidate> aggregateByOutfit(List<RagFlowClient.RagFlowChunk> chunks) {
        Map<String, Double> bestScore = new LinkedHashMap<>();
        for (RagFlowClient.RagFlowChunk raw : chunks) {
            String docName = raw.documentName() != null && !raw.documentName().isBlank()
                    ? raw.documentName() : raw.documentKeyword();
            String id = extractIdFromDocName(docName);
            double score = Math.min(1.0, Math.max(0.0, raw.similarity()));
            bestScore.merge(id, score, Math::max);
        }
        List<Candidate> list = new ArrayList<>();
        for (Map.Entry<String, Double> e : bestScore.entrySet()) {
            list.add(new Candidate(e.getKey(), e.getValue()));
        }
        return list;
    }

    /**
     * 多路 RRF 召回：与生产 RagFlowKnowledgeService.retrieveOutfitPool 同口径。
     * 3 路：HyDE/原话主路 + decomposedQueries 子查询路 + 中文映射路。
     * 按 RRF 1/(60+rank) 累加融合，保留 max similarity 作 semanticScore。
     *
     * <p>诊断开关：环境变量 {@code BENCH_SINGLE_ROUTE=true} 时只跑 HyDE 主路，
     * 用于隔离测试 HyDE 单路收益 vs HyDE+RRF 多路收益。
     */
    private List<Candidate> retrieveMultiRouteRrf(AnalyzedQuery aq) {
        String r1 = buildSearchQuestion(aq);
        boolean singleRoute = "true".equalsIgnoreCase(System.getenv("BENCH_SINGLE_ROUTE"));
        if (singleRoute) {
            System.out.println("[BENCH] single-route mode (HyDE only): " + truncate(r1));
            return aggregateByOutfit(ragFlowClient.retrieve(r1, 50, "", 0.2));
        }
        String r2 = buildDecomposedRoute(aq);
        String r3 = buildCnMappedRoute(aq);

        List<List<Candidate>> routes = new ArrayList<>();
        List<String> routeTexts = new ArrayList<>();
        if (!r1.isBlank()) {
            routes.add(aggregateByOutfit(ragFlowClient.retrieve(r1, 50, "", 0.2)));
            routeTexts.add(r1);
        }
        if (!r2.isBlank() && !r2.equals(r1)) {
            try {
                routes.add(aggregateByOutfit(ragFlowClient.retrieve(r2, 50, "", 0.2)));
                routeTexts.add(r2);
            } catch (Exception e) {
                System.out.println("ROUTE_FAIL | route=decomposed | err=" + e.getMessage());
            }
        }
        if (!r3.isBlank() && !r3.equals(r1) && !r3.equals(r2)) {
            try {
                routes.add(aggregateByOutfit(ragFlowClient.retrieve(r3, 50, "", 0.2)));
                routeTexts.add(r3);
            } catch (Exception e) {
                System.out.println("ROUTE_FAIL | route=cn_mapped | err=" + e.getMessage());
            }
        }
        if (routes.isEmpty()) return new ArrayList<>();
        if (routes.size() == 1) return routes.get(0);
        return rrfFuseCandidates(routes);
    }

    /**
     * RRF 融合多个 Candidate 列表：每个 outfit 累加 1/(60+rank)，
     * 按 RRF 总分降序返回，semanticScore 取每路中最高 similarity。
     * 截断到 top-50 与单路候选池规模保持一致（避免低 sim 边缘 outfit 挤掉高 sim）。
     */
    private List<Candidate> rrfFuseCandidates(List<List<Candidate>> routes) {
        final double k = 60.0;
        Map<String, Double> rrfScore = new HashMap<>();
        Map<String, Double> bestSimilarity = new LinkedHashMap<>();
        for (List<Candidate> route : routes) {
            for (int rank = 0; rank < route.size(); rank++) {
                Candidate c = route.get(rank);
                String id = c.outfitId();
                if (id == null || id.isBlank()) id = "unknown";
                rrfScore.merge(id, 1.0 / (k + rank), Double::sum);
                bestSimilarity.merge(id, c.semanticScore(), Math::max);
            }
        }
        return rrfScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(50)
                .map(e -> new Candidate(e.getKey(), bestSimilarity.getOrDefault(e.getKey(), 0.0)))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    private String buildDecomposedRoute(AnalyzedQuery aq) {
        if (aq.decomposedQueries() == null || aq.decomposedQueries().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String sub : aq.decomposedQueries()) {
            if (sub != null && !sub.isBlank()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(sub.trim());
            }
        }
        return sb.toString().trim();
    }

    private String buildCnMappedRoute(AnalyzedQuery aq) {
        StringBuilder sb = new StringBuilder();
        if (aq.originalQuery() != null && !aq.originalQuery().isBlank()) sb.append(aq.originalQuery());
        AnalyzedQuery.QueryParams p = aq.params();
        if (p != null) {
            if (p.scene() != null && !p.scene().isBlank()) {
                sb.append(" ").append(SCENE_CN.getOrDefault(p.scene(), p.scene()));
            }
            if (p.styleHint() != null && !p.styleHint().isBlank()) sb.append(" ").append(p.styleHint());
            if (p.season() != null && !p.season().isBlank() && !"UNKNOWN".equalsIgnoreCase(p.season())) {
                sb.append(" ").append(SEASON_CN.getOrDefault(p.season(), p.season()));
            }
        }
        return sb.toString().trim();
    }

    private static final java.util.Map<String, String> SCENE_CN = java.util.Map.of(
            "WORKPLACE", "通勤/办公", "COMMUTE", "通勤",
            "FORMAL_EVENT", "婚礼/正式晚宴", "SCHOOL", "校园/上学",
            "TRAVEL", "旅行/出差", "OUTDOOR", "户外/运动/海边", "DAILY", "日常/休闲");
    private static final java.util.Map<String, String> SEASON_CN = java.util.Map.of(
            "SPRING", "春季", "SUMMER", "夏季", "AUTUMN", "秋季", "WINTER", "冬季");

    private String extractIdFromDocName(String docName) {
        if (docName == null) return "unknown";
        int underscore = docName.lastIndexOf('_');
        int dot = docName.lastIndexOf('.');
        if (underscore >= 0 && dot > underscore) {
            String id = docName.substring(underscore + 1, dot);
            int paren = id.indexOf('(');
            return paren > 0 ? id.substring(0, paren) : id;
        }
        return docName;
    }

    private Map<String, OutfitTags> loadOutfitTags() throws IOException {
        Map<String, OutfitTags> tags = new HashMap<>();
        Path docsDir = Paths.get(FASHION_DOCS_DIR);
        if (!Files.isDirectory(docsDir)) return tags;
        try (var stream = Files.list(docsDir)) {
            stream.filter(p -> p.getFileName().toString().matches("outfit_\\d+\\.md"))
                    .forEach(p -> {
                        String outfitId = extractOutfitIdFromFileName(p.getFileName().toString());
                        if (outfitId == null) return;
                        try {
                            String md = Files.readString(p);
                            Matcher m = OVERVIEW_PATTERN.matcher(md);
                            if (m.find()) {
                                String overview = m.group(1).strip();
                                tags.put(outfitId, parseOutfitTags(overview));
                            }
                        } catch (IOException e) {
                            // ignore
                        }
                    });
        }
        return tags;
    }

    private static String extractOutfitIdFromFileName(String fileName) {
        Matcher m = Pattern.compile("outfit_(\\d+)\\.md").matcher(fileName);
        return m.matches() ? m.group(1) : null;
    }

    private static OutfitTags parseOutfitTags(String overview) {
        List<String> styles = extractList(overview, "整体风格[:：]\\s*(.+?)\\s*(?:\\n|$)");
        List<String> seasons = extractList(overview, "适合季节[:：]\\s*(.+?)\\s*(?:\\n|$)");
        List<String> scenes = extractList(overview, "适合场合[:：]\\s*(.+?)\\s*(?:\\n|$)");
        double formality = extractDouble(overview, "整体正式度[:：]\\s*([\\d.]+)/5");
        return new OutfitTags(scenes, seasons, styles, formality);
    }

    private static List<String> extractList(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        if (!m.find()) return List.of();
        String raw = m.group(1).strip();
        if (raw.isEmpty()) return List.of();
        return List.of(raw.split("[/、，,\\s]+"));
    }

    private static double extractDouble(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 0.0;
    }

    private List<RealQuery> loadQueries() {
        return jdbc.query(
                "SELECT user_input, reference_outfit_id FROM fashion_conversations "
                        + "WHERE reference_outfit_id <> '' ORDER BY id DESC",
                (rs, rowNum) -> new RealQuery(rs.getString("user_input"), normalizeOutfitId(rs.getString("reference_outfit_id"))));
    }

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

    /**
     * 与生产 {@code RagFlowKnowledgeService.buildSearchQuestion} 保持一致：
     * HyDE 开关（环境变量 BENCH_HYDE_ENABLED=true 开启）：true 时用 hyde + 用户原话，
     * 否则走旧拼接。评测必须与生产同口径，否则测出的收益是检索词构造差异而非真实业务效果。
     *
     * <p>2026-08-23 评测结论：HyDE 默认关闭（baseline 21→12-16%），待 outfit 级
     * chunking 重做后复测。代码保留通过环境变量启用。
     */
    private static String buildSearchQuestion(AnalyzedQuery query) {
        boolean hydeEnabled = "true".equalsIgnoreCase(System.getenv("BENCH_HYDE_ENABLED"));
        String hyde = query.hypotheticalOutfit();
        if (hydeEnabled && hyde != null && !hyde.isBlank()) {
            StringBuilder sb = new StringBuilder(hyde.trim());
            if (query.originalQuery() != null && !query.originalQuery().isBlank()
                    && !hyde.contains(query.originalQuery())) {
                sb.append(" ").append(query.originalQuery().trim());
            }
            return sb.toString();
        }
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
        if (sb.isEmpty() && query.decomposedQueries() != null) {
            for (String sub : query.decomposedQueries()) {
                if (sub != null && !sub.isBlank()) sb.append(sub).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private int findRank(List<Candidate> ranked, String gt) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).outfitId().equals(gt)) return i;
        }
        return -1;
    }

    private static String key(Weights w) {
        return String.format(Locale.US, "sem=%.2f scene=%.2f season=%.2f style=%.2f formality=%.2f cap=%.2f",
                w.semanticWeight(), w.sceneWeight(), w.seasonWeight(), w.styleWeight(), w.formalityWeight(), w.ruleScoreCap());
    }

    private static String pct(int n, int d) {
        return d == 0 ? "0.0%" : String.format(Locale.US, "%.2f%%", 100.0 * n / d);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    private void savePools(List<QueryPool> pools, Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("query\tgt\tscene\tseason\tstyleHint\tformality\tpool");
        for (QueryPool pool : pools) {
            AnalyzedQuery.QueryParams p = pool.analyzed.params();
            String poolStr = pool.candidates.stream()
                    .map(c -> c.outfitId() + ":" + String.format(Locale.US, "%.4f", c.semanticScore()))
                    .collect(Collectors.joining(","));
            lines.add(String.format("%s\t%s\t%s\t%s\t%s\t%d\t%s",
                    pool.query.userInput().replace("\t", " ").replace("\n", " "),
                    pool.query.groundTruth(),
                    p != null ? p.scene() : "",
                    p != null ? p.season() : "",
                    p != null ? p.styleHint() : "",
                    p != null ? p.formality() : 0,
                    poolStr));
        }
        Files.write(path, lines);
        System.out.println("候选池已保存: " + path.toAbsolutePath());
    }
}
