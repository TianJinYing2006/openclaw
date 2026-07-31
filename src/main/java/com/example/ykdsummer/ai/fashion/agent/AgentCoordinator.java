package com.example.ykdsummer.ai.fashion.agent;

import com.example.ykdsummer.ai.fashion.model.*;
import com.example.ykdsummer.ai.fashion.rag.FashionKnowledgeService;
import com.example.ykdsummer.ai.fashion.rag.QueryAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多 Agent 编排器（核心）。
 *
 * <p>控制 Agent 调用顺序、并行策略、上下文传递和确定性降级。
 *
 * <p>编排流程：
 * <pre>
 * Step 1: QueryAnalyzer 分析需求
 * Step 2: FashionKnowledgeService RAG 检索（一次查询，全局共享）
 * Step 3: Stylist 生成方案（串行，必须先有方案）
 * Step 4: Critic ∥ Trend 并行评审（fork/join）
 * Step 5: Coordinator 综合裁决
 * </pre>
 *
 * <p>降级策略：
 * <ul>
 *   <li>QueryAnalyzer 失败 → 关键词兜底</li>
 *   <li>RAG 失败 → 空知识列表，Agent 依赖自身知识</li>
 *   <li>Stylist 失败 → 安全兜底方案</li>
 *   <li>Critic 失败 → 空评审，Coordinator 自行判断</li>
 *   <li>Trend 失败 → 中性趋势分，不影响流程</li>
 *   <li>Coordinator 失败 → 降级到 Stylist 首选方案</li>
 * </ul>
 */
@Component
public class AgentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentCoordinator.class);

    private final QueryAnalyzer queryAnalyzer;
    private final FashionKnowledgeService knowledgeService;
    private final StylistAgent stylistAgent;
    private final CriticAgent criticAgent;
    private final TrendAgent trendAgent;
    private final CoordinatorAgent coordinatorAgent;
    private final ExecutorService parallelExecutor;

    public AgentCoordinator(QueryAnalyzer queryAnalyzer,
                            FashionKnowledgeService knowledgeService,
                            StylistAgent stylistAgent,
                            CriticAgent criticAgent,
                            TrendAgent trendAgent,
                            CoordinatorAgent coordinatorAgent) {
        this.queryAnalyzer = queryAnalyzer;
        this.knowledgeService = knowledgeService;
        this.stylistAgent = stylistAgent;
        this.criticAgent = criticAgent;
        this.trendAgent = trendAgent;
        this.coordinatorAgent = coordinatorAgent;
        this.parallelExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 执行完整的穿搭推荐管道。
     *
     * @param request 用户请求
     * @return 穿搭推荐结果
     */
    public FashionResult process(FashionRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Fashion pipeline started for user: {}", request.userId());

        // Step 1: 查询分析
        AnalyzedQuery query = queryAnalyzer.analyze(request.userInput());
        log.info("Step 1 done: QueryAnalyzer");

        // Step 2: RAG 知识检索（一次查询，全局共享）
        List<RetrievedChunk> chunks = knowledgeService.retrieve(query);
        String ragContext = knowledgeService.formatContext(chunks);
        log.info("Step 2 done: RAG retrieved {} chunks", chunks.size());

        // Step 3: Stylist 生成方案（串行，必须先有方案）
        StylistOutput stylist = stylistAgent.execute(request, ragContext, query);
        log.info("Step 3 done: Stylist generated {} suggestions",
                stylist != null && !stylist.isEmpty() ? stylist.suggestions().size() : 0);

        if (stylist == null || stylist.isEmpty()) {
            log.error("Stylist failed, returning safety fallback");
            return FashionResult.safetyFallback(
                    query.params() != null ? query.params().scene() : "daily", query);
        }

        // Step 4: Critic ∥ Trend 并行评审
        CompletableFuture<CriticOutput> criticFuture = CompletableFuture.supplyAsync(
                () -> criticAgent.execute(stylist, query), parallelExecutor);

        CompletableFuture<TrendOutput> trendFuture = CompletableFuture.supplyAsync(
                () -> trendAgent.execute(stylist, query), parallelExecutor);

        CriticOutput critic = criticFuture.exceptionally(e -> {
            log.warn("Critic failed, using empty fallback: {}", e.getMessage());
            return CriticOutput.empty();
        }).join();

        TrendOutput trend = trendFuture.exceptionally(e -> {
            log.warn("Trend failed, using neutral fallback: {}", e.getMessage());
            return TrendOutput.neutral();
        }).join();

        log.info("Step 4 done: Critic ({} reviews) + Trend ({} analyses) parallel complete",
                critic.reviews() != null ? critic.reviews().size() : 0,
                trend.trendAnalysis() != null ? trend.trendAnalysis().size() : 0);

        // Step 5: Coordinator 综合裁决
        CoordinatorOutput coordinator = coordinatorAgent.execute(
                request, stylist, critic, trend, ragContext);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Step 5 done: Coordinator. Pipeline completed in {}ms", elapsed);

        // Coordinator 失败 → 降级到 Stylist 首选方案
        if (coordinator == null) {
            log.warn("Coordinator failed, degrading to Stylist first suggestion");
            return buildDegradedResult(stylist, critic, trend, ragContext, query,
                    "Coordinator 超时，降级为 Stylist 首选方案");
        }

        return new FashionResult(
                true, coordinator, stylist, critic, trend, ragContext, query, null, false
        );
    }

    /**
     * 构造降级结果（Coordinator 失败时，用 Stylist 首选方案）。
     */
    private FashionResult buildDegradedResult(StylistOutput stylist, CriticOutput critic,
                                               TrendOutput trend, String ragContext,
                                               AnalyzedQuery query, String errorMessage) {
        // 用 Stylist 第一个方案构造一个简易的 CoordinatorOutput
        StylistOutput.OutfitSuggestion first = stylist.suggestions().get(0);
        CoordinatorOutput.RefinedOutfit refined = new CoordinatorOutput.RefinedOutfit(
                first.outfit() != null ? first.outfit().top() : "",
                first.outfit() != null ? first.outfit().bottom() : "",
                first.outfit() != null ? first.outfit().shoes() : "",
                first.outfit() != null ? first.outfit().accessories() : ""
        );
        CoordinatorOutput.FinalRecommendation rec = new CoordinatorOutput.FinalRecommendation(
                first.id(), "Coordinator 降级，直接采用 Stylist 首选方案", java.util.Map.of()
        );
        CoordinatorOutput coordOutput = new CoordinatorOutput(
                rec, refined, first.reasoning(), List.of()
        );

        return new FashionResult(
                false, coordOutput, stylist, critic, trend, ragContext, query,
                errorMessage, true
        );
    }
}
