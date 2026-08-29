package com.wechatbot.fashion.ai.fashion.look.agent;

import com.wechatbot.fashion.ai.fashion.look.model.*;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionConversationService;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionEmbeddingService;
import com.wechatbot.fashion.ai.fashion.look.profile.UserProfileService;
import com.wechatbot.fashion.ai.fashion.look.rag.FashionKnowledgeService;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.ai.fashion.look.FashionResponseFormatter;
import com.wechatbot.fashion.common.fashion.ReferenceImageResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多 Agent 编排器（核心）。
 *
 * <p>控制 Agent 调用顺序、并行策略、上下文传递和确定性降级。
 *
 * <p>编排流程：
 * <pre>
 * Step 1: QueryAnalyzer 分析需求        → 失败则关键词兜底
 * Step 2: FashionKnowledgeService RAG   → 失败则空知识
 * Step 3: Stylist 生成方案（串行）       → 失败则安全兜底
 * Step 4: Critic ∥ Trend 并行评审       → 各自失败互不影响（仅复杂请求）
 * Step 5: Coordinator 综合裁决          → 失败则降级 Stylist 首选（仅复杂请求）
 * </pre>
 *
 * <p>动态路由：简单请求（正式度 ≤3 且子查询 ≤3）跳过 Step 4/5，直接采用 Stylist 结果，
 * 将 5 次 LLM 调用降为 2 次，显著降低延迟。
 *
 * <p>全链路降级链：
 * <pre>
 * QueryAnalyzer失败 → 关键词兜底
 * RAG失败 → 空知识上下文
 * Stylist失败 → FashionResult 安全兜底方案
 * Critic失败 → 空评审
 * Trend失败 → 中性趋势数据
 * Coordinator失败 → Stylist首选方案
 * </pre>
 */
@Component
public class AgentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentCoordinator.class);

    /** 历史滑动窗口：检索时排除最近 N 次推荐已命中过的参考穿搭，降低跨次重复率。 */
    private static final int RECENT_RECOMMENDATION_WINDOW = 5;

    /** RAG 上下文中的穿搭编号标记，如 [outfit_231]。 */
    private static final Pattern OUTFIT_ID_MARKER = Pattern.compile("\\[outfit_(\\d+)\\]");

    private final QueryAnalyzer queryAnalyzer;
    private final FashionKnowledgeService knowledgeService;
    private final StylistAgent stylistAgent;
    private final CriticAgent criticAgent;
    private final TrendAgent trendAgent;
    private final CoordinatorAgent coordinatorAgent;
    private final FashionConversationService conversationService;
    private final FashionResponseFormatter formatter;
    private final UserProfileService userProfileService;
    private final FashionEmbeddingService embeddingService;
    private final ReferenceImageResolver referenceImageResolver;
    private final ExecutorService parallelExecutor;

    public AgentCoordinator(QueryAnalyzer queryAnalyzer,
                            FashionKnowledgeService knowledgeService,
                            StylistAgent stylistAgent,
                            CriticAgent criticAgent,
                            TrendAgent trendAgent,
                            CoordinatorAgent coordinatorAgent,
                            FashionConversationService conversationService,
                            FashionResponseFormatter formatter,
                            UserProfileService userProfileService,
                            FashionEmbeddingService embeddingService,
                            ReferenceImageResolver referenceImageResolver,
                            @Qualifier("fashionAgentParallelExecutor") ExecutorService parallelExecutor) {
        this.queryAnalyzer = queryAnalyzer;
        this.knowledgeService = knowledgeService;
        this.stylistAgent = stylistAgent;
        this.criticAgent = criticAgent;
        this.trendAgent = trendAgent;
        this.coordinatorAgent = coordinatorAgent;
        this.conversationService = conversationService;
        this.formatter = formatter;
        this.userProfileService = userProfileService;
        this.embeddingService = embeddingService;
        this.referenceImageResolver = referenceImageResolver;
        this.parallelExecutor = parallelExecutor;
    }

    /**
     * 执行完整的穿搭推荐管道。
     *
     * @param request 用户请求
     * @return 穿搭推荐结果
     */
    public FashionResult process(FashionRequest request) {
        long pipelineStart = System.currentTimeMillis();
        Map<String, Long> timings = new LinkedHashMap<>();
        log.info("Fashion pipeline started for user: {}", request.userId());

        // ── Step 0: 获取用户画像上下文（向量相似度检索） ──
        String profileContext = userProfileService.buildProfileContext(
                request.userId(), request.userInput());
        if (!profileContext.isBlank()) {
            log.info("User profile context loaded: {} chars", profileContext.length());
        }

        // ── Step 1: 查询分析（注入画像上下文） ──
        long step = System.currentTimeMillis();
        AnalyzedQuery query;
        try {
            query = queryAnalyzer.analyze(request.userInput(), profileContext);
        } catch (Exception e) {
            log.warn("Step 1 QueryAnalyzer exception, using keyword fallback: {}", e.getMessage());
            query = AnalyzedQuery.fallback(request.userInput());
        }
        timings.put("query_analyze", System.currentTimeMillis() - step);
        boolean simpleRequest = isSimpleRequest(query);
        log.info("Step 1 done: scene={}, season={}, simple={} ({}ms)",
                query.params() != null ? query.params().scene() : "null",
                query.params() != null ? query.params().season() : "null",
                simpleRequest,
                timings.get("query_analyze"));

        // ── 保存穿搭对话记录（用户画像数据源） ──
        Long conversationId = null;
        if (query.params() != null) {
            conversationId = conversationService.saveInitial(
                    request.userId(),
                    request.userInput(),
                    query.params().scene(),
                    query.params().season(),
                    query.params().formality()
            );
        }

        // ── Step 2: RAG 知识检索（历史滑动窗口去重：排除最近已推荐过的参考） ──
        step = System.currentTimeMillis();
        List<RetrievedChunk> chunks;
        try {
            Set<String> excludeIds = new HashSet<>(
                    conversationService.findRecentReferenceOutfits(
                            request.userId(), RECENT_RECOMMENDATION_WINDOW));
            if (!excludeIds.isEmpty()) {
                log.info("Excluding {} recently recommended outfit(s) from retrieval", excludeIds.size());
            }
            chunks = knowledgeService.retrieveExcluding(query, excludeIds);
        } catch (Exception e) {
            log.warn("Step 2 RAG exception, using empty context: {}", e.getMessage());
            chunks = List.of();
        }
        String ragContext = knowledgeService.formatContext(chunks);
        timings.put("rag_retrieve", System.currentTimeMillis() - step);
        log.info("Step 2 done: {} chunks ({}ms)", chunks.size(), timings.get("rag_retrieve"));

        // ── Step 3: Stylist 生成方案（串行） ──
        step = System.currentTimeMillis();
        StylistOutput stylist;
        try {
            stylist = stylistAgent.execute(request, ragContext, query, profileContext);
        } catch (Exception e) {
            log.warn("Step 3 Stylist exception: {}", e.getMessage());
            stylist = null;
        }
        timings.put("stylist", System.currentTimeMillis() - step);
        log.info("Step 3 done: {} suggestions ({}ms)",
                stylist != null && !stylist.isEmpty() ? stylist.suggestions().size() : 0,
                timings.get("stylist"));

        if (stylist == null || stylist.isEmpty()) {
            log.error("Stylist failed, returning safety fallback");
            timings.put("total", System.currentTimeMillis() - pipelineStart);
            logTimings(timings);
            String scene = query.params() != null ? query.params().scene() : "DAILY";
            FashionResult fallback = FashionResult.safetyFallback(scene, query);
            persistConversation(conversationId, request.userInput(), fallback);
            return fallback;
        }

        // ── 动态路由：简单请求直接返回 Stylist 结果，跳过 Critic/Trend/Coordinator ──
        if (simpleRequest) {
            timings.put("total", System.currentTimeMillis() - pipelineStart);
            logTimings(timings);
            log.info("Simple request detected, skipping Critic/Trend/Coordinator");
            FashionResult simple = buildSimpleResult(stylist, ragContext, query);
            persistConversation(conversationId, request.userInput(), simple);
            return simple;
        }

        // ── Step 4: Critic ∥ Trend 并行评审 ──
        step = System.currentTimeMillis();
        final StylistOutput finalStylist = stylist;
        final AnalyzedQuery finalQuery = query;

        CompletableFuture<CriticOutput> criticFuture = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return criticAgent.execute(finalStylist, finalQuery);
                    } catch (Exception e) {
                        log.warn("Critic exception: {}", e.getMessage());
                        return CriticOutput.empty();
                    }
                }, parallelExecutor);

        CompletableFuture<TrendOutput> trendFuture = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return trendAgent.execute(finalStylist, finalQuery);
                    } catch (Exception e) {
                        log.warn("Trend exception: {}", e.getMessage());
                        return TrendOutput.neutral();
                    }
                }, parallelExecutor);

        CriticOutput critic = criticFuture.exceptionally(e -> {
            log.warn("Critic failed: {}", e.getMessage());
            return CriticOutput.empty();
        }).join();

        TrendOutput trend = trendFuture.exceptionally(e -> {
            log.warn("Trend failed: {}", e.getMessage());
            return TrendOutput.neutral();
        }).join();

        timings.put("parallel_review", System.currentTimeMillis() - step);
        log.info("Step 4 done: Critic={} reviews, Trend={} analyses ({}ms)",
                critic.reviews() != null ? critic.reviews().size() : 0,
                trend.trendAnalysis() != null ? trend.trendAnalysis().size() : 0,
                timings.get("parallel_review"));

        // ── Step 5: Coordinator 综合裁决 ──
        step = System.currentTimeMillis();
        CoordinatorOutput coordinator;
        try {
            // Coordinator 只需知道每个 [outfit_XXX] 的编号与完整搭配概要，
            // 传精简版 RAG 上下文（丢弃冗长单品详情），降低 prompt 长度与耗时
            coordinator = coordinatorAgent.execute(request, stylist, critic, trend, compactRagContext(ragContext));
        } catch (Exception e) {
            log.warn("Step 5 Coordinator exception: {}", e.getMessage());
            coordinator = null;
        }
        timings.put("coordinator", System.currentTimeMillis() - step);
        timings.put("total", System.currentTimeMillis() - pipelineStart);
        logTimings(timings);

        // Coordinator 失败 → 降级到 Stylist 首选方案
        if (coordinator == null) {
            log.warn("Coordinator failed, degrading to Stylist first suggestion");
            FashionResult degraded = buildDegradedResult(stylist, critic, trend, ragContext, query,
                    "Coordinator 超时，降级为 Stylist 首选方案");
            persistConversation(conversationId, request.userInput(), degraded);
            return degraded;
        }

        FashionResult finalResult = new FashionResult(
                true, coordinator, stylist, critic, trend, ragContext, query, null, false
        );
        persistConversation(conversationId, request.userInput(), finalResult);
        return finalResult;
    }

    /**
     * 简单请求判定：正式度 ≤3 且子查询 ≤3 视为简单，跳过 Critic/Trend/Coordinator。
     *
     * <p>以 {@link AnalyzedQuery} 的 formality 与 decomposedQueries 数量作为复杂度依据。
     */
    static boolean isSimpleRequest(AnalyzedQuery query) {
        if (query == null || query.params() == null) return true;
        int formality = query.params().formality();
        int subQueries = query.decomposedQueries() == null ? 0 : query.decomposedQueries().size();
        return formality <= 3 && subQueries <= 3;
    }

    /** 构造简单请求结果：成功、未降级，Coordinator 用 Stylist 首选方案补齐。 */
    private FashionResult buildSimpleResult(StylistOutput stylist, String ragContext, AnalyzedQuery query) {
        return new FashionResult(
                true, coordinatorFromStylist(stylist), stylist,
                CriticOutput.empty(), TrendOutput.neutral(), ragContext, query, null, false
        );
    }

    /**
     * 精简 RAG 上下文供 Coordinator 使用：保留每个 {@code [outfit_XXX]} 编号与
     * 【完整搭配】概要，丢弃【单品详情】等冗长内容，降低最终裁决轮的 prompt 长度与耗时。
     */
    static String compactRagContext(String ragContext) {
        if (ragContext == null || ragContext.isBlank()) {
            return ragContext;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : ragContext.split("(?=\\[outfit_)")) {
            int detailIdx = part.indexOf("【单品详情】");
            sb.append(detailIdx > 0 ? part.substring(0, detailIdx) : part);
        }
        return sb.toString().strip();
    }

    /** 统一保存对话摘要 + 异步 Embedding 回填。 */
    private void persistConversation(Long conversationId, String userInput, FashionResult result) {
        String summary = formatter.summarize(result);
        conversationService.updateRecommendation(conversationId, summary);
        conversationService.updateReferenceOutfit(conversationId, resolveEffectiveOutfitId(result));
        saveEmbeddingAsync(conversationId, userInput, summary);
    }

    /**
     * 校正"试穿"指代消解用的 outfit 编号。
     *
     * <p>Coordinator 可能输出参考图库中不存在的编号（LLM 幻觉，如 028），
     * 导致后续试穿查不到单品图；此时回退到 RAG 上下文实际命中的 [outfit_XXX] 编号，
     * 保证发图与试穿使用同一套有效编号。
     */
    private String resolveEffectiveOutfitId(FashionResult result) {
        String coordinatorId = extractReferenceOutfitId(result);
        if (hasUsableGarments(coordinatorId)) {
            return coordinatorId;
        }
        String ragId = result == null ? null : extractOutfitIdFromRagContext(result.ragContext());
        if (ragId != null && hasUsableGarments(ragId)) {
            log.info("Correcting reference outfit {} -> {} (coordinator id not in image map, use RAG hit)",
                    coordinatorId, ragId);
            return ragId;
        }
        return coordinatorId;
    }

    /** 编号在参考图库中是否存在可用于试穿的单品图（含规范化处理）。 */
    private boolean hasUsableGarments(String rawId) {
        if (rawId == null || rawId.isBlank() || referenceImageResolver == null) {
            return false;
        }
        String normalized = normalizeOutfitId(rawId);
        return !normalized.isBlank() && !referenceImageResolver.garmentsFor(normalized).isEmpty();
    }

    /** 从 RAG 上下文提取第一个 [outfit_XXX] 编号；无标记返回 null。 */
    private String extractOutfitIdFromRagContext(String ragContext) {
        if (ragContext == null || ragContext.isBlank()) {
            return null;
        }
        Matcher matcher = OUTFIT_ID_MARKER.matcher(ragContext);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 将 "002"/"outfit_002"/"[outfit_002]" 统一为 "002"（与 image_urls.json 的 key 格式对齐）。 */
    private static String normalizeOutfitId(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replace("[outfit_", "").replace("outfit_", "").replace("]", "").trim();
        try {
            int num = Integer.parseInt(cleaned);
            return String.format("%03d", num);
        } catch (NumberFormatException e) {
            return cleaned;
        }
    }

    /** 从最终方案中提取 outfit 编号，供后续"试穿"指代消解（编号在 service 层统一规范化）。 */
    private static String extractReferenceOutfitId(FashionResult result) {
        if (result == null || result.coordinator() == null
                || result.coordinator().refinedOutfit() == null) {
            return "";
        }
        String refId = result.coordinator().refinedOutfit().referenceOutfitId();
        return refId == null ? "" : refId;
    }

    /**
     * 异步生成对话 Embedding 并回填到数据库（不阻塞管道返回）。
     *
     * <p>Embedding 文本 = 用户输入 + 推荐摘要，使向量同时反映"问了什么"和"推荐了什么"。
     */
    private void saveEmbeddingAsync(Long conversationId, String userInput, String recommendation) {
        if (conversationId == null) return;
        String embeddingText = userInput + " " + (recommendation != null ? recommendation : "");
        CompletableFuture.runAsync(() -> {
            try {
                float[] embedding = embeddingService.embed(embeddingText);
                if (embedding != null) {
                    conversationService.updateEmbedding(
                            conversationId, embeddingService.serialize(embedding));
                    log.debug("Embedding saved for conversation {}", conversationId);
                }
            } catch (Exception e) {
                log.warn("Async embedding save failed for conversation {}: {}",
                        conversationId, e.getMessage());
            }
        }, parallelExecutor);
    }

    /** 用 Stylist 第一个方案构造简易 CoordinatorOutput（简单请求与降级共用）。 */
    private static CoordinatorOutput coordinatorFromStylist(StylistOutput stylist) {
        StylistOutput.OutfitSuggestion first = stylist.suggestions().get(0);
        CoordinatorOutput.RefinedOutfit refined = new CoordinatorOutput.RefinedOutfit(
                first.outfit() != null ? first.outfit().top() : "",
                first.outfit() != null ? first.outfit().bottom() : "",
                first.outfit() != null ? first.outfit().shoes() : "",
                first.outfit() != null ? first.outfit().accessories() : "",
                first.referenceOutfitId()
        );
        CoordinatorOutput.FinalRecommendation rec = new CoordinatorOutput.FinalRecommendation(
                first.id(), "直接采用 Stylist 首选方案", java.util.Map.of()
        );
        return new CoordinatorOutput(rec, refined, first.reasoning(), List.of());
    }

    /**
     * 构造降级结果（Coordinator 失败时，用 Stylist 首选方案）。
     */
    private FashionResult buildDegradedResult(StylistOutput stylist, CriticOutput critic,
                                               TrendOutput trend, String ragContext,
                                               AnalyzedQuery query, String errorMessage) {
        return new FashionResult(
                false, coordinatorFromStylist(stylist), stylist, critic, trend, ragContext, query,
                errorMessage, true
        );
    }

    private void logTimings(Map<String, Long> timings) {
        StringBuilder sb = new StringBuilder("Pipeline timings:");
        for (Map.Entry<String, Long> entry : timings.entrySet()) {
            sb.append(String.format(" %s=%dms", entry.getKey(), entry.getValue()));
        }
        log.info(sb.toString());
    }
}
