package com.wechatbot.fashion.ai.fashion.look.rag;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.RetrievedChunk;
import com.wechatbot.fashion.ai.fashion.look.model.SeedEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * 基于 RAGFlow 的穿搭知识检索（主实现）。
 *
 * <p>通过 RAGFlow 的 {@code /api/v1/retrieval} 接口进行向量+关键词混合检索，
 * 替代 MySQL FULLTEXT。RAGFlow 负责文档解析、分块、Embedding 和索引，
 * Java 侧只需发送检索请求并转换结果。
 *
 * <p>仅在 {@code app.fashion.rag.provider=ragflow} 时激活。
 * RAGFlow 未完整配置时，自动降级返回空上下文（不阻断管道）。
 *
 * <p>检索词构建策略：RAGFlow 是语义检索，不需要像 MySQL FTS 那样拆词。
 * 直接用原始查询 + 场景/风格/季节参数拼成自然语言检索词。
 *
 * <p>上下文增强：RAGFlow 返回的是文档分块，可能只含部分单品信息。
 * 启动时从 {@code data/fashion_docs/outfit_*.md} 加载每套穿搭的完整概览，
 * 在 {@link #formatContext} 中为每个 chunk 补充完整搭配信息，确保 Stylist
 * 能看到每套穿搭的全部单品（上衣+下装+鞋子），避免图文脱节。
 */
@Component
@ConditionalOnProperty(name = "app.fashion.rag.provider", havingValue = "ragflow")
public class RagFlowKnowledgeService implements FashionKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(RagFlowKnowledgeService.class);
    private static final String FASHION_DOCS_DIR = "data/fashion_docs";

    /** 匹配 markdown 文档中的"整套搭配概览"段落。 */
    private static final Pattern OVERVIEW_PATTERN = Pattern.compile(
            "## 整套搭配概览\\s*\\n(.*?)(?=\\n## |\\Z)", Pattern.DOTALL);

    private final RagFlowClient ragFlowClient;
    private final RagFlowProperties properties;
    private final FashionRagDiversityProperties diversity;

    /** outfit_id → 完整搭配概览文本（从 markdown 文档提取）。 */
    private volatile Map<String, String> outfitOverviews = Map.of();
    /** outfit_id → 结构化标签（场景/季节/风格/正式度），用于规则重排。 */
    private volatile Map<String, OutfitTags> outfitTags = Map.of();

    public RagFlowKnowledgeService(RagFlowClient ragFlowClient,
            RagFlowProperties properties, FashionRagDiversityProperties diversity) {
        this.ragFlowClient = ragFlowClient;
        this.properties = properties;
        this.diversity = diversity;
    }

    /**
     * Outfit 结构化标签，从 markdown 文档的"整套搭配概览"段落解析。
     */
    private record OutfitTags(List<String> scenes, List<String> seasons,
                              List<String> styles, double formality) {}

    @PostConstruct
    public void loadOutfitOverviews() {
        Map<String, String> overviews = new HashMap<>();
        Map<String, OutfitTags> tags = new HashMap<>();
        Path docsDir = Paths.get(FASHION_DOCS_DIR);
        if (!Files.isDirectory(docsDir)) {
            log.warn("Fashion docs directory not found: {} (outfit overview enrichment disabled)", FASHION_DOCS_DIR);
            return;
        }
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
                                overviews.put(outfitId, overview);
                                tags.put(outfitId, parseOutfitTags(overview));
                            }
                        } catch (IOException e) {
                            log.warn("Failed to read outfit doc {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list fashion docs: {}", e.getMessage());
        }
        this.outfitOverviews = overviews;
        this.outfitTags = tags;
        log.info("Loaded {} outfit overviews and tags from {}", overviews.size(), FASHION_DOCS_DIR);
    }

    /**
     * 从"整套搭配概览"段落解析结构化标签。
     */
    private static OutfitTags parseOutfitTags(String overview) {
        List<String> styles = extractList(overview, "整体风格[:：]\\s*(.+?)\\s*(?:\\n|$)");
        List<String> seasons = extractList(overview, "适合季节[:：]\\s*(.+?)\\s*(?:\\n|$)");
        List<String> scenes = extractList(overview, "适合场合[:：]\\s*(.+?)\\s*(?:\\n|$)");
        double formality = extractDouble(overview, "整体正式度[:：]\\s*([\\d.]+)/5");
        return new OutfitTags(scenes, seasons, styles, formality);
    }

    private static List<String> extractList(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        if (!m.find()) {
            return List.of();
        }
        String raw = m.group(1).strip();
        if (raw.isEmpty()) {
            return List.of();
        }
        return List.of(raw.split("[/、，,\\s]+"));
    }

    private static double extractDouble(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return 0.0;
    }

    private static String extractOutfitIdFromFileName(String fileName) {
        // outfit_003.md → 003
        Matcher m = Pattern.compile("outfit_(\\d+)\\.md").matcher(fileName);
        return m.matches() ? m.group(1) : null;
    }

    @Override
    public List<RetrievedChunk> retrieve(AnalyzedQuery query) {
        return retrieveExcluding(query, Set.of());
    }

    /**
     * 调试用检索：返回原始候选池 → 规则重排 → 最终结果三段，每段保留分数。
     *
     * <p>供 /admin/retrieval 调试台使用，面试时直观展示 RAG 检索链路各阶段分数变化。
     * 不排除历史推荐（调试场景需要看到完整候选池），但复用多样性采样。
     *
     * @param query QueryAnalyzer 输出的结构化查询
     * @return 三段结果 + 检索词 + 配置标记；query 为空时返回空三段
     */
    public RetrievalDebug retrieveForDebug(AnalyzedQuery query) {
        boolean ruleEnabled = properties.isRuleRerankEnabled();
        boolean multiRoute = properties.isMultiRouteEnabled();
        int topK = properties.getTopK();
        if (query == null) {
            return new RetrievalDebug("", null, List.of(), List.of(), List.of(),
                    ruleEnabled, multiRoute, topK);
        }
        String searchQuestion = buildSearchQuestion(query);
        if (searchQuestion.isBlank()) {
            return new RetrievalDebug("", query, List.of(), List.of(), List.of(),
                    ruleEnabled, multiRoute, topK);
        }

        // 1. 原始 outfit 候选池（聚合后、重排前）
        List<RetrievedChunk> rawChunks = new ArrayList<>(retrieveOutfitPool(query, searchQuestion));
        List<OutfitScore> rawScores = toOutfitScoresFromChunks(rawChunks, 0.0, 0.0);

        // 2. 规则重排（保留 semantic/rule/final 三种分数）
        List<OutfitScore> rerankedScores;
        List<RetrievedChunk> rerankedChunks;
        if (ruleEnabled && query.params() != null && !rawChunks.isEmpty()) {
            List<ScoredOutfit> scored = scoreByRules(rawChunks, query);
            rerankedScores = toOutfitScoresFromScored(scored);
            rerankedChunks = scored.stream().map(s -> s.chunk).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        } else {
            rerankedScores = rawScores;
            rerankedChunks = new ArrayList<>(rawChunks);
        }

        // 3. 最终结果（多样性采样 / top-k 截断，不排除历史）
        List<RetrievedChunk> finalChunks;
        if (diversity.isEnabled()) {
            finalChunks = RetrievalDiversitySampler.sample(rerankedChunks, topK, diversity.getMinScore());
        } else if (rerankedChunks.size() > topK) {
            finalChunks = new ArrayList<>(rerankedChunks.subList(0, topK));
        } else {
            finalChunks = new ArrayList<>(rerankedChunks);
        }
        // 最终结果沿用重排后的分数；未重排时用语义分填充
        java.util.Map<String, OutfitScore> rerankMap = new java.util.HashMap<>();
        for (OutfitScore s : rerankedScores) {
            rerankMap.put(s.outfitId(), s);
        }
        List<OutfitScore> finalScores = new ArrayList<>(finalChunks.size());
        for (int i = 0; i < finalChunks.size(); i++) {
            RetrievedChunk c = finalChunks.get(i);
            String outfitId = c.entry().id() == null ? "unknown" : c.entry().id();
            OutfitScore base = rerankMap.getOrDefault(outfitId, new OutfitScore(outfitId,
                    c.entry().source() == null ? "" : c.entry().source(),
                    truncate(c.entry().summary(), 220),
                    c.score(), 0.0, c.score(), 0));
            finalScores.add(new OutfitScore(base.outfitId(), base.docName(), base.summary(),
                    base.semanticScore(), base.ruleScore(), base.finalScore(), i + 1));
        }

        return new RetrievalDebug(searchQuestion, query, rawScores, rerankedScores,
                finalScores, ruleEnabled, multiRoute, topK);
    }

    /** 对 outfit 候选池执行规则重排，返回带分数的 ScoredOutfit 列表（保留所有中间分数）。 */
    private List<ScoredOutfit> scoreByRules(List<RetrievedChunk> outfitChunks, AnalyzedQuery query) {
        double semanticWeight = Math.max(0.0, Math.min(1.0, properties.getRuleRerankSemanticWeight()));
        double ruleWeight = 1.0 - semanticWeight;
        AnalyzedQuery.QueryParams params = query.params();
        List<ScoredOutfit> scored = new ArrayList<>(outfitChunks.size());
        for (RetrievedChunk chunk : outfitChunks) {
            String outfitId = chunk.entry().id();
            OutfitTags tags = outfitTags.get(outfitId);
            double ruleScore = (tags == null) ? 0.0 : computeRuleScore(params, tags);
            double finalScore = semanticWeight * chunk.score() + ruleWeight * ruleScore;
            scored.add(new ScoredOutfit(chunk, finalScore, ruleScore));
        }
        scored.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
        return scored;
    }

    private static List<OutfitScore> toOutfitScoresFromChunks(List<RetrievedChunk> chunks,
                                                              double ruleScore, double finalScore) {
        List<OutfitScore> scores = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            scores.add(new OutfitScore(
                    c.entry().id() == null ? "unknown" : c.entry().id(),
                    c.entry().source() == null ? "" : c.entry().source(),
                    truncate(c.entry().summary(), 220),
                    c.score(), ruleScore, finalScore, i + 1));
        }
        return scores;
    }

    private static List<OutfitScore> toOutfitScoresFromScored(List<ScoredOutfit> scored) {
        List<OutfitScore> scores = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            ScoredOutfit s = scored.get(i);
            RetrievedChunk c = s.chunk;
            scores.add(new OutfitScore(
                    c.entry().id() == null ? "unknown" : c.entry().id(),
                    c.entry().source() == null ? "" : c.entry().source(),
                    truncate(c.entry().summary(), 220),
                    c.score(), s.ruleScore, s.finalScore, i + 1));
        }
        return scores;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** 调试用单条 outfit 分数快照，保留语义分 / 规则分 / 融合分。 */
    public record OutfitScore(
            String outfitId,
            String docName,
            String summary,
            double semanticScore,
            double ruleScore,
            double finalScore,
            int rank
    ) {}

    /** 检索调试三段结果：原始候选池 → 规则重排 → 最终输出。 */
    public record RetrievalDebug(
            String searchQuestion,
            AnalyzedQuery analyzedQuery,
            List<OutfitScore> rawOutfits,
            List<OutfitScore> rerankedOutfits,
            List<OutfitScore> finalOutfits,
            boolean ruleRerankEnabled,
            boolean multiRouteEnabled,
            int topK
    ) {}

    @Override
    public List<RetrievedChunk> retrieveExcluding(AnalyzedQuery query, java.util.Collection<String> excludeIds) {
        if (query == null) {
            return List.of();
        }

        String searchQuestion = buildSearchQuestion(query);
        if (searchQuestion.isBlank()) {
            log.warn("Empty search question, returning empty results");
            return List.of();
        }

        // Outfit 级检索：取 outfit 候选池（多路 RRF 或单路兜底）
        List<RetrievedChunk> outfitChunks = retrieveOutfitPool(query, searchQuestion);
        log.info("RAGFlow retrieved {} outfits for question: {}",
                outfitChunks.size(), truncate(searchQuestion));

        // 1. 结构化规则重排：用 query 的 scene/season/style/formality 对 outfit 候选池微调排序
        if (properties.isRuleRerankEnabled() && query.params() != null) {
            outfitChunks = rerankByRules(outfitChunks, query);
            log.info("Reranked {} outfit(s) by structured rules for question: {}",
                    outfitChunks.size(), truncate(searchQuestion));
        }

        // 2. 历史滑动窗口：排除最近已推荐过的 outfit，避免跨次重复
        if (excludeIds != null && !excludeIds.isEmpty()) {
            int before = outfitChunks.size();
            outfitChunks.removeIf(c -> c.entry().id() != null && excludeIds.contains(c.entry().id()));
            log.debug("Excluded {} recently recommended outfit(s), {} remain", before - outfitChunks.size(), outfitChunks.size());
        }

        // 3. 多样性采样在 outfit 层进行（关闭时直接取 top-k）
        int topK = properties.getTopK();
        if (diversity.isEnabled()) {
            outfitChunks = RetrievalDiversitySampler.sample(outfitChunks, topK, diversity.getMinScore());
        } else if (outfitChunks.size() > topK) {
            outfitChunks = outfitChunks.subList(0, topK);
        }

        log.info("RAGFlow returning {} outfit(s) for question: {}", outfitChunks.size(), truncate(searchQuestion));
        return outfitChunks;
    }

    /**
     * 获取 outfit 级候选池：多路 RRF 融合 或 单路兜底。
     *
     * <p>多路 RRF 启用时并行检索 3 路：
     * <ol>
     *   <li>主路 {@code buildSearchQuestion}（HyDE + 原话，语义最丰富）</li>
     *   <li>子查询路 {@code buildDecomposedRoute}（decomposedQueries 拼接，中英混合 token）</li>
     *   <li>中文映射路 {@code buildCnMappedRoute}（scene/season 翻成中文）</li>
     * </ol>
     * 每路独立召回 + outfit 聚合，再按 RRF 公式 {@code 1/(k+rank)} 累加融合。
     * 每个 outfit 保留各路 max similarity 作 semanticScore（不破坏 rule rerank 量纲）。
     */
    private List<RetrievedChunk> retrieveOutfitPool(AnalyzedQuery query, String mainRoute) {
        if (!properties.isMultiRouteEnabled()) {
            // 单路兜底：原 retrieve + aggregate
            List<RagFlowClient.RagFlowChunk> rawChunks = ragFlowClient.retrieve(mainRoute, properties.getRetrievalPageSize());
            List<RetrievedChunk> rawEntries = new ArrayList<>(rawChunks.size());
            for (RagFlowClient.RagFlowChunk raw : rawChunks) {
                SeedEntry entry = toSeedEntry(raw);
                double score = Math.min(1.0, Math.max(0.0, raw.similarity()));
                rawEntries.add(new RetrievedChunk(entry, score));
            }
            return aggregateByOutfit(rawEntries);
        }

        // 多路召回
        List<String> routes = new ArrayList<>();
        routes.add(mainRoute);
        String decomposedRoute = buildDecomposedRoute(query);
        if (!decomposedRoute.isBlank() && !decomposedRoute.equals(mainRoute)) {
            routes.add(decomposedRoute);
        }
        String cnMappedRoute = buildCnMappedRoute(query);
        if (!cnMappedRoute.isBlank() && !cnMappedRoute.equals(mainRoute)) {
            routes.add(cnMappedRoute);
        }

        int pageSize = properties.getRetrievalPageSize();
        List<List<RetrievedChunk>> routeOutfitPools = new ArrayList<>();
        for (String route : routes) {
            try {
                List<RagFlowClient.RagFlowChunk> rawChunks = ragFlowClient.retrieve(route, pageSize);
                List<RetrievedChunk> entries = new ArrayList<>(rawChunks.size());
                for (RagFlowClient.RagFlowChunk raw : rawChunks) {
                    SeedEntry entry = toSeedEntry(raw);
                    double score = Math.min(1.0, Math.max(0.0, raw.similarity()));
                    entries.add(new RetrievedChunk(entry, score));
                }
                List<RetrievedChunk> pool = aggregateByOutfit(entries);
                log.info("Multi-route: route='{}' raw={} chunks -> {} outfits",
                        truncate(route), rawChunks.size(), pool.size());
                routeOutfitPools.add(pool);
            } catch (Exception e) {
                log.warn("Multi-route retrieve failed for route='{}': {}", truncate(route), e.getMessage());
            }
        }
        if (routeOutfitPools.isEmpty()) {
            return List.of();
        }
        if (routeOutfitPools.size() == 1) {
            return routeOutfitPools.get(0);
        }
        return rrfFuse(routeOutfitPools);
    }

    /**
     * RRF 多路融合：对每个 outfit 累加 {@code 1/(k+rank_in_each_route)}，
     * 按 RRF 总分降序返回，semanticScore 取每路中该 outfit 的最高 similarity
     * （保留原 RAGFlow 量纲，不破坏 rule rerank 公式）。
     *
     * <p>RRF 是信息检索标准做法（Cormack et al. 2009），按 rank 倒数加权而非轮询，
     * 高位候选不会被低位候选稀释——这是修正 P3 V4 round-robin 退步的关键差异。
     *
     * <p><b>截断到 top-50（与单路 page_size 一致）</b>：3 路召回后去重 outfit 数
     * 可达 80~100，若不截断会把大量低 similarity 的边缘 outfit 送进 rule rerank
     * 候选池，挤掉单路时本在 top-5 内的高 sim outfit（实测"婚礼"gt=139 从 rank=5
     * 掉到 rank=64，top-5 命中率从 21% 跌到 10%）。
     */
    private List<RetrievedChunk> rrfFuse(List<List<RetrievedChunk>> routePools) {
        final double k = properties.getMultiRouteRrfK();
        Map<String, RetrievedChunk> bestChunkByOutfit = new LinkedHashMap<>();
        Map<String, Double> rrfScoreByOutfit = new HashMap<>();

        for (List<RetrievedChunk> route : routePools) {
            for (int rank = 0; rank < route.size(); rank++) {
                RetrievedChunk chunk = route.get(rank);
                String outfitId = chunk.entry().id();
                if (outfitId == null || outfitId.isBlank()) outfitId = "unknown";
                rrfScoreByOutfit.merge(outfitId, 1.0 / (k + rank), Double::sum);
                // 保留各路中该 outfit 的最高 similarity chunk（供 rule rerank 用）
                RetrievedChunk existing = bestChunkByOutfit.get(outfitId);
                if (existing == null || chunk.score() > existing.score()) {
                    bestChunkByOutfit.put(outfitId, chunk);
                }
            }
        }

        // 按 RRF 分数降序输出，保留 max similarity 作 score（不动 rule rerank 量纲），
        // 截断到 top-50 与单路候选池规模保持一致
        int limit = Math.min(properties.getRetrievalPageSize(),
                rrfScoreByOutfit.size());
        return rrfScoreByOutfit.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> bestChunkByOutfit.get(e.getKey()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    /**
     * 子查询路检索词：把 decomposedQueries 拼成一段文本。
     * 中英混合 token 适合 RAGFlow 的 BM25（0.7 权重）召回。
     */
    private String buildDecomposedRoute(AnalyzedQuery query) {
        if (query.decomposedQueries() == null || query.decomposedQueries().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String sub : query.decomposedQueries()) {
            if (sub != null && !sub.isBlank()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(sub.trim());
            }
        }
        return sb.toString().trim();
    }

    /**
     * 中文映射路检索词：scene/season 翻成中文 + styleHint 中文 + originalQuery。
     *
     * <p>失败模式分析显示，FORMAL_EVENT/OUTDOOR 这类英文枚举跟 outfit 文档
     * 的"日常/休闲"标签无词命中，BM25 权重 0.7 召回失败。中文映射路把
     * "FORMAL_EVENT" 翻成"婚礼/正式晚宴"，让 BM25 有中文 token 可命中，
     * 给向量检索额外信号。
     */
    private String buildCnMappedRoute(AnalyzedQuery query) {
        StringBuilder sb = new StringBuilder();
        if (query.originalQuery() != null && !query.originalQuery().isBlank()) {
            sb.append(query.originalQuery());
        }
        if (query.params() != null) {
            String scene = query.params().scene();
            if (scene != null && !scene.isBlank()) {
                String cn = SCENE_CN_MAP.getOrDefault(scene, scene);
                if (!cn.isBlank()) sb.append(" ").append(cn);
            }
            String style = query.params().styleHint();
            if (style != null && !style.isBlank()) sb.append(" ").append(style);
            String season = query.params().season();
            if (season != null && !season.isBlank()
                    && !"UNKNOWN".equalsIgnoreCase(season)) {
                String cn = SEASON_CN_MAP.getOrDefault(season, season);
                if (!cn.isBlank()) sb.append(" ").append(cn);
            }
        }
        return sb.toString().trim();
    }

    private static final java.util.Map<String, String> SCENE_CN_MAP = java.util.Map.of(
            "WORKPLACE", "通勤/办公",
            "COMMUTE", "通勤",
            "FORMAL_EVENT", "婚礼/正式晚宴",
            "SCHOOL", "校园/上学",
            "TRAVEL", "旅行/出差",
            "OUTDOOR", "户外/运动/海边",
            "DAILY", "日常/休闲"
    );
    private static final java.util.Map<String, String> SEASON_CN_MAP = java.util.Map.of(
            "SPRING", "春季",
            "SUMMER", "夏季",
            "AUTUMN", "秋季",
            "WINTER", "冬季"
    );

    /**
     * 按 outfit 聚合 chunk：同一 outfit 取相似度最高的 chunk 作为代表。
     *
     * <p>RAGFlow 返回的是文档分块，一个 outfit 文档会被切成多个单品 chunk。
     * 直接对 chunk 排序会导致同一个 outfit 占用多个 top-k 槽位，且评分口径是
     * "单品匹配度"而非"整套搭配匹配度"。聚合后每个 outfit 只保留一个代表 chunk，
     * 分数取该 outfit 下所有 chunk 的最高分。</p>
     */
    private List<RetrievedChunk> aggregateByOutfit(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Map<String, RetrievedChunk> bestByOutfit = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            String outfitId = chunk.entry().id();
            if (outfitId == null || outfitId.isBlank()) {
                outfitId = "unknown";
            }
            RetrievedChunk existing = bestByOutfit.get(outfitId);
            if (existing == null || chunk.score() > existing.score()) {
                bestByOutfit.put(outfitId, chunk);
            }
        }
        // 保持原始相关性降序（RAGFlow 已按 hybrid score 排序，最高分代表 chunk 的顺序即 outfit 顺序）
        return new ArrayList<>(bestByOutfit.values());
    }

    /**
     * 基于结构化参数的规则重排。
     *
     * <p>将 QueryAnalyzer 提取的 scene/season/style/formality 与 outfit 文档标签匹配，
     * 生成 0~1 规则分，再与原始语义分做加权融合。规则分仅作为微调，避免分数量纲冲突。</p>
     */
    private List<RetrievedChunk> rerankByRules(List<RetrievedChunk> outfitChunks, AnalyzedQuery query) {
        if (outfitChunks == null || outfitChunks.isEmpty()) {
            return List.of();
        }
        List<ScoredOutfit> scored = scoreByRules(outfitChunks, query);

        // 临时诊断日志：输出前 10 名的语义分、规则分、融合分
        for (ScoredOutfit s : scored.subList(0, Math.min(10, scored.size()))) {
            log.info("Rule rerank score: outfit={} semantic={} rule={} final={}",
                    s.chunk.entry().id(),
                    String.format(java.util.Locale.US, "%.3f", s.chunk.score()),
                    String.format(java.util.Locale.US, "%.3f", s.ruleScore),
                    String.format(java.util.Locale.US, "%.3f", s.finalScore));
        }

        return scored.stream().map(s -> s.chunk).toList();
    }

    private record ScoredOutfit(RetrievedChunk chunk, double finalScore, double ruleScore) {}

    /**
     * 计算规则匹配分（0~1）。
     *
     * <p>维度采用"放过策略"：query 未指定某维度时，该维度不参与加分，也不扣分。
     * 场景是穿搭需求中最强的结构化信号，因此权重最高；季节/风格/正式度作为辅助。
     * 规则分封顶 0.8，避免对语义分造成过大扰动。</p>
     */
    private static double computeRuleScore(AnalyzedQuery.QueryParams params, OutfitTags tags) {
        double score = 0.0;

        if (params.scene() != null && !params.scene().isBlank()) {
            if (matchesScene(params.scene(), tags.scenes())) {
                score += 0.40;
            }
        }
        if (params.season() != null && !params.season().isBlank()) {
            if (matchesSeason(params.season(), tags.seasons())) {
                score += 0.15;
            }
        }
        if (params.styleHint() != null && !params.styleHint().isBlank()) {
            if (matchesStyle(params.styleHint(), tags.styles())) {
                score += 0.15;
            }
        }
        if (params.formality() > 0 && tags.formality() > 0) {
            if (Math.abs(params.formality() - tags.formality()) <= 1.0) {
                score += 0.10;
            }
        }

        return Math.min(0.8, score);
    }

    private static boolean matchesScene(String queryScene, List<String> outfitScenes) {
        Set<String> normalizedOutfitScenes = outfitScenes.stream()
                .map(RagFlowKnowledgeService::normalizeSceneCn)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return normalizedOutfitScenes.contains(queryScene.toUpperCase());
    }

    private static boolean matchesSeason(String querySeason, List<String> outfitSeasons) {
        Set<String> normalizedOutfitSeasons = outfitSeasons.stream()
                .map(RagFlowKnowledgeService::normalizeSeasonCn)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return normalizedOutfitSeasons.contains(querySeason.toUpperCase());
    }

    private static boolean matchesStyle(String queryStyleHint, List<String> outfitStyles) {
        if (queryStyleHint == null || queryStyleHint.isBlank()) {
            return false;
        }
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

    /**
     * 将 AnalyzedQuery 转换为 RAGFlow 检索词。
     *
     * <p>HyDE 优先（{@code hydeEnabled=true}）：如果 {@link AnalyzedQuery#hypotheticalOutfit()}
     * 非空（LLM 生成的假设 outfit 描述），用「HyDE 描述 + 用户原话」作为检索词。
     *
     * <p>HyDE 关闭（默认）：走旧拼接逻辑，原查询 + 场景:SCENE + 风格:STYLE + 季节:SEASON。
     *
     * <p><b>2026-08-23 评测结论</b>：HyDE 在当前 naive chunk 配置下退步（baseline 21% → 12-16%），
     * 因为 chunk 是单品级、HyDE 描述是整套语义，语义距离天然远；HyDE 描述里通用词
     * （"西装/礼服/连衣裙"）被 BM25 召回大量不相关 outfit。默认关闭，待 outfit 级
     * chunking 重做后复测。
     */
    private String buildSearchQuestion(AnalyzedQuery query) {
        String hyde = query.hypotheticalOutfit();
        if (properties.isHydeEnabled() && hyde != null && !hyde.isBlank()) {
            // HyDE 描述作为主语义锚点 + 用户原话作为关键词命中 token
            StringBuilder sb = new StringBuilder(hyde.trim());
            if (query.originalQuery() != null && !query.originalQuery().isBlank()
                    && !hyde.contains(query.originalQuery())) {
                sb.append(" ").append(query.originalQuery().trim());
            }
            return sb.toString();
        }

        // 旧逻辑兜底
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

        // 如果 originalQuery 为空但有 decomposedQueries，用它们兜底
        if (sb.isEmpty() && query.decomposedQueries() != null) {
            for (String sub : query.decomposedQueries()) {
                if (sub != null && !sub.isBlank()) {
                    sb.append(sub).append(" ");
                }
            }
        }

        return sb.toString().trim();
    }

    /**
     * 将 RAGFlow chunk 转换为 SeedEntry（复用现有模型）。
     *
     * <p>RAGFlow 返回的 content 是 Markdown 分块的原文，文档名在
     * {@code document_keyword}（如 outfit_002.md）。我们把 content 放入 summary，
     * 从文档名提取编号作为 ID。
     */
    private SeedEntry toSeedEntry(RagFlowClient.RagFlowChunk raw) {
        // retrieval 响应没有 document_name，文档名在 document_keyword
        String docName = raw.documentName() != null && !raw.documentName().isBlank()
                ? raw.documentName()
                : raw.documentKeyword();
        if (docName == null) {
            docName = "unknown";
        }
        // outfit_002.md → 002
        String id = extractIdFromDocName(docName);

        return new SeedEntry(
                id,
                "ragflow_chunk",
                docName,
                null,           // outfit — RAGFlow 返回的是文本块，不是结构化单品
                null,           // tags
                raw.content(),  // summary — RAGFlow chunk 的完整文本
                null,
                null,
                null
        );
    }

    private String extractIdFromDocName(String docName) {
        if (docName == null) return "unknown";
        // outfit_002.md → 002；兼容重名后的 outfit_002(1).md → 002
        int underscore = docName.lastIndexOf('_');
        int dot = docName.lastIndexOf('.');
        if (underscore >= 0 && dot > underscore) {
            String id = docName.substring(underscore + 1, dot);
            int paren = id.indexOf('(');
            return paren > 0 ? id.substring(0, paren) : id;
        }
        return docName;
    }

    @Override
    public String formatContext(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "暂无相关穿搭参考";
        }

        StringBuilder sb = new StringBuilder("## 穿搭知识参考\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            String outfitId = chunk.entry().id();
            sb.append(i + 1).append(". ");

            // 前置 outfit 编号标记（如 [outfit_002]），供发图链路解析 top-1 参考穿搭
            sb.append("[outfit_").append(outfitId).append("] ");

            // 补充完整穿搭概览（RAGFlow chunk 可能只含部分单品信息）
            String overview = outfitOverviews.get(outfitId);
            if (overview != null && !overview.isBlank()) {
                sb.append("\n【完整搭配】\n").append(overview).append("\n");
            }

            // RAGFlow chunk 的 content 已经是格式化的 Markdown 文本
            // 作为单品详细信息附加在概览之后
            String content = chunk.entry().summary();
            if (content != null && !content.isBlank()) {
                sb.append("\n【单品详情】\n").append(content);
            } else {
                // 兜底：用 toPromptText
                sb.append(chunk.toPromptText());
            }
            sb.append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
