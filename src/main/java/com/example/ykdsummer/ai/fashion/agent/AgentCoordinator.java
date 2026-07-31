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
    private static final String MOCK_STYLIST_TRIGGER = "测试穿搭假数据";

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
        StylistOutput stylist = shouldUseMockStylist(request)
                ? mockStylistOutput(query)
                : stylistAgent.execute(request, ragContext, query);
        log.info("Step 3 done: Stylist generated {} suggestions",
                stylist != null && !stylist.isEmpty() ? stylist.suggestions().size() : 0);

        if (stylist == null || stylist.isEmpty()) {
            log.error("Stylist failed, returning safety fallback");
            return FashionResult.safetyFallback(
                    query.params() != null ? query.params().scene() : "DAILY", query);
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

    private boolean shouldUseMockStylist(FashionRequest request) {
        return request != null
                && request.userInput() != null
                && request.userInput().contains(MOCK_STYLIST_TRIGGER);
    }

    private StylistOutput mockStylistOutput(AnalyzedQuery query) {
        String scene = query != null && query.params() != null ? query.params().scene() : "";
        if ("wedding".equals(scene)) {
            log.info("Using mock StylistOutput for wedding test");
            return mockWeddingStylistOutput();
        }
        log.info("Using mock StylistOutput for beach test");
        return mockBeachStylistOutput();
    }

    private StylistOutput mockBeachStylistOutput() {
        return new StylistOutput(List.of(
                new StylistOutput.OutfitSuggestion(
                        1,
                        "清爽防晒风",
                        new StylistOutput.Outfit(
                                "浅蓝色宽松防晒衬衫，内搭白色吊带背心",
                                "白色高腰短裤",
                                "防水凉鞋",
                                "草编遮阳帽、墨镜、防水斜挎包"
                        ),
                        "浅蓝、白色、米色",
                        "适合海边防晒、散步和拍照，整体轻盈清爽。",
                        List.of("海边散步", "拍照", "短途旅行"),
                        "高腰短裤能提高腰线，宽松衬衫对肩臂线条更友好。"
                ),
                new StylistOutput.OutfitSuggestion(
                        2,
                        "度假连衣裙风",
                        new StylistOutput.Outfit(
                                "珊瑚橘吊带连衣裙外搭轻薄罩衫",
                                "连衣裙一体式下装",
                                "细带平底凉鞋",
                                "贝壳项链、编织托特包、宽檐帽"
                        ),
                        "珊瑚橘、奶白、浅金色",
                        "照片表现力强，适合海边咖啡店、落日拍照和轻度游玩。",
                        List.of("度假拍照", "海边咖啡", "傍晚散步"),
                        "外搭罩衫可以修饰手臂，连衣裙要注意腰线位置。"
                ),
                new StylistOutput.OutfitSuggestion(
                        3,
                        "基础运动休闲风",
                        new StylistOutput.Outfit(
                                "黑色紧身速干 T 恤",
                                "深色五分运动短裤",
                                "普通网面运动鞋",
                                "棒球帽、运动手环"
                        ),
                        "黑色、深灰",
                        "行动方便，但颜色偏深，在海边白天容易吸热，拍照氛围较弱。",
                        List.of("赶路", "轻运动", "临时出行"),
                        "紧身上衣对上半身线条要求较高，五分裤可能压低身高比例。"
                )
        ));
    }

    private StylistOutput mockWeddingStylistOutput() {
        return new StylistOutput(List.of(
                new StylistOutput.OutfitSuggestion(
                        1,
                        "热门露肤街头风",
                        new StylistOutput.Outfit(
                                "露脐吊带背心",
                                "浅蓝色牛仔短裤",
                                "厚底凉拖",
                                "金属腰链、小腋下包"
                        ),
                        "浅蓝、银色、黑色",
                        "趋势感强，但过于休闲和暴露，不适合正式婚礼。",
                        List.of("音乐节", "海边派对", "街拍"),
                        "露腰和短裤对身材要求高，也容易削弱正式感。"
                ),
                new StylistOutput.OutfitSuggestion(
                        2,
                        "优雅婚礼宾客风",
                        new StylistOutput.Outfit(
                                "香槟色缎面吊带裙，外搭轻薄短西装",
                                "中长款连衣裙一体式下装",
                                "裸色低跟凉鞋",
                                "珍珠耳饰、精致手拿包"
                        ),
                        "香槟色、裸色、珍珠白",
                        "兼顾婚礼场合得体度、轻盈感和当下缎面趋势。",
                        List.of("婚礼宾客", "晚宴", "正式约会"),
                        "短西装能修饰肩颈和手臂，中长裙摆更稳妥得体。"
                ),
                new StylistOutput.OutfitSuggestion(
                        3,
                        "正式通勤套装风",
                        new StylistOutput.Outfit(
                                "黑色修身西装外套，内搭白衬衫",
                                "黑色直筒西裤",
                                "黑色尖头高跟鞋",
                                "极简腕表、黑色通勤包"
                        ),
                        "黑色、白色",
                        "正式度足够，但婚礼氛围偏沉闷，喜庆感和轻盈感不足。",
                        List.of("商务会议", "正式通勤", "面试"),
                        "直线条显利落，但整体容易显严肃。"
                )
        ));
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
