package com.wechatbot.fashion.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatbot.fashion.ai.fashion.look.agent.CoordinatorAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.CriticAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.StylistAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.TrendAgent;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CoordinatorOutput;
import com.wechatbot.fashion.ai.fashion.look.model.CriticOutput;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.fashion.look.model.TrendOutput;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionConversationService;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionEmbeddingService;
import com.wechatbot.fashion.ai.fashion.look.profile.UserProfileService;
import com.wechatbot.fashion.ai.fashion.look.rag.FashionKnowledgeService;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.common.fashion.ReferenceImageResolver;
import com.wechatbot.fashion.ai.fashion.look.FashionResponseFormatter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 故障注入测试（评审证据：可靠性/韧性）。
 *
 * <p>对图内各依赖注入失败，验证图不崩溃、按设计降级：
 * <ol>
 *   <li>Stylist 抛异常 → 安全兜底（STYLIST_FAILED → responder 预填 RESULT）；</li>
 *   <li>Coordinator 抛超时 → 降级为 Stylist 首选，errorMessage 含"超时"；</li>
 *   <li>Critic/Trend 并行评审抛异常 → 中性降级、图正常完成；</li>
 *   <li>RAG 检索抛异常 → 降级为空上下文，不阻断管道（RagNode 内置 catch）。</li>
 * </ol>
 * 纯单测：全部依赖 Mockito stub，图编译不带 checkpoint（无 Redis），确定性可重复。
 */
class FaultInjectionGraphTest {

    private static final String QUERY = "我要去海边婚礼穿什么，帮我搭一套正式的礼服";

    record Rigs(FashionGraphContext ctx, StylistAgent stylist, CriticAgent critic,
                TrendAgent trend, CoordinatorAgent coordinator, FashionKnowledgeService knowledge) {}

    /** 默认全绿 mocks（与 FashionGraphRedisRecoveryTest.mockContext 同构），各测试按需注入故障。 */
    private static Rigs rigs() {
        QueryAnalyzer queryAnalyzer = mock(QueryAnalyzer.class);
        when(queryAnalyzer.analyze(anyString(), anyString()))
                .thenReturn(AnalyzedQuery.fallback(QUERY));

        FashionKnowledgeService knowledge = mock(FashionKnowledgeService.class);
        when(knowledge.retrieveExcluding(any(), any())).thenReturn(List.of());
        when(knowledge.formatContext(any())).thenReturn("rag context [outfit_002]");

        StylistAgent stylist = mock(StylistAgent.class);
        when(stylist.execute(any(), anyString(), any(), anyString())).thenReturn(sampleStylist());

        CriticAgent critic = mock(CriticAgent.class);
        when(critic.execute(any(), any())).thenReturn(new CriticOutput(List.of(
                new CriticOutput.Critique(1, 5, Map.of(), List.of(), List.of(), List.of(), List.of()))));

        TrendAgent trend = mock(TrendAgent.class);
        when(trend.execute(any(), any())).thenReturn(TrendOutput.neutral());

        CoordinatorAgent coordinator = mock(CoordinatorAgent.class);
        when(coordinator.execute(any(), any(), any(), any(), anyString()))
                .thenReturn(FashionResultBuilders.fromStylist(sampleStylist()));

        FashionConversationService conversationService = mock(FashionConversationService.class);
        when(conversationService.findRecentReferenceOutfits(anyString(), anyInt())).thenReturn(List.of());
        when(conversationService.saveInitial(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(1L);

        UserProfileService userProfileService = mock(UserProfileService.class);
        when(userProfileService.buildProfileContext(anyString(), anyString())).thenReturn("");

        FashionEmbeddingService embeddingService = mock(FashionEmbeddingService.class);
        when(embeddingService.embed(anyString())).thenReturn(new float[1]);
        when(embeddingService.serialize(any())).thenReturn("[]");

        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        when(referenceImageResolver.garmentsFor(anyString())).thenReturn(List.of());

        FashionResponseFormatter formatter = mock(FashionResponseFormatter.class);
        when(formatter.summarize(any())).thenReturn("summary");

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        FashionGraphContext ctx = new FashionGraphContext(queryAnalyzer, knowledge, stylist, critic, trend,
                coordinator, conversationService, userProfileService, embeddingService, referenceImageResolver,
                formatter, executor, new ObjectMapper(), false, 2, true, false, null, null, null);
        return new Rigs(ctx, stylist, critic, trend, coordinator, knowledge);
    }

    private static FashionResult run(FashionGraphContext ctx) throws Exception {
        CompiledGraph graph = FashionGraphDefinition.build(ctx).compile(CompileConfig.builder().build());
        Map<String, Object> inputs = FashionState.initialInputs("fault-user", QUERY);
        Optional<OverAllState> state = graph.invoke(inputs, com.alibaba.cloud.ai.graph.RunnableConfig.builder().build());
        assertTrue(state.isPresent(), "图必须返回最终状态，不许崩溃");
        return state.flatMap(s -> FashionState.readJson(s, FashionState.RESULT, FashionResult.class, new ObjectMapper()))
                .orElseThrow(() -> new AssertionError("最终状态中必须有 RESULT"));
    }

    @Test
    void stylistThrowsStillReturnsSafetyFallback() throws Exception {
        Rigs r = rigs();
        doThrow(new RuntimeException("LLM provider 5xx")).when(r.stylist).execute(any(), anyString(), any(), anyString());

        FashionResult result = run(r.ctx);

        assertNotNull(result, "Stylist 失败也必须出结果（安全兜底）");
        assertTrue(result.degraded() || !result.success(), "兜底结果应标记降级");
        assertNotNull(result.errorMessage(), "兜底结果应携带错误信息");
    }

    @Test
    void coordinatorTimeoutDegradesWithTimeoutMessage() throws Exception {
        Rigs r = rigs();
        // 用 RuntimeException 携带 "timed out" 消息模拟超时（isTimeout 按消息判定）
        doThrow(new RuntimeException("Coordinator timed out after 30s"))
                .when(r.coordinator).execute(any(), any(), any(), any(), anyString());

        FashionResult result = run(r.ctx);

        assertTrue(result.degraded(), "Coordinator 超时必须降级");
        assertTrue(result.errorMessage() != null && result.errorMessage().contains("超时"),
                "降级文案应反映超时而非笼统异常，实际: " + result.errorMessage());
        assertNotNull(result.coordinator(), "降级后仍应提供 Stylist 首选方案（fromStylist）");
    }

    @Test
    void criticAndTrendFailuresDoNotBreakGraph() throws Exception {
        Rigs r = rigs();
        doThrow(new RuntimeException("critic boom")).when(r.critic).execute(any(), any());
        doThrow(new RuntimeException("trend boom")).when(r.trend).execute(any(), any());

        FashionResult result = run(r.ctx);

        assertTrue(result.success(), "评审异常不应阻断主流程");
        assertFalse(result.degraded(), "评审异常走中性降级，不算结果降级");
        assertNotNull(result.coordinator());
    }

    @Test
    void ragFailureDegradesToEmptyContext() throws Exception {
        Rigs r = rigs();
        doThrow(new RuntimeException("RAGFlow 宕机")).when(r.knowledge).retrieveExcluding(any(), any());

        FashionResult result = run(r.ctx);

        assertTrue(result.success(), "RAG 异常时图不崩、正常给出推荐（RagNode 捕获后降级为空上下文）");
        assertNotNull(result.ragContext(), "检索失败后上下文不可为 null");
        assertFalse(result.degraded(), "RAG 侧降级不应触发整条结果降级标记");
    }

    private static StylistOutput sampleStylist() {
        return new StylistOutput(List.of(new StylistOutput.OutfitSuggestion(
                1, "优雅浪漫风",
                new StylistOutput.Outfit("白衬衫", "半身裙", "小白鞋", "丝巾"),
                "白+粉", "理由", List.of("婚礼"), "均码", "002")));
    }
}