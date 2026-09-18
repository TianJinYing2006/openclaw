package com.wechatbot.fashion.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatbot.fashion.ai.fashion.look.agent.CoordinatorAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.CriticAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.StylistAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.TrendAgent;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CriticOutput;
import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.fashion.look.model.TrendOutput;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionConversationService;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionEmbeddingService;
import com.wechatbot.fashion.ai.fashion.look.profile.UserProfileService;
import com.wechatbot.fashion.ai.fashion.look.rag.FashionKnowledgeService;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.ai.fashion.look.FashionResponseFormatter;
import com.wechatbot.fashion.common.fashion.ReferenceImageResolver;
import org.junit.jupiter.api.Test;
import com.wechatbot.fashion.graph.trajectory.AgentTrajectoryRecorder;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 3 守门测试：真实 Fashion 子图（6 节点全跑）+ 状态经 Redis checkpoint 持久化（重启可恢复）。
 *
 * <p>用 Mockito stub 全部 agent / 服务 Bean，构造 {@link FashionGraphContext}，驱动整图走深路径
 * （analyzedQuery 含"婚礼/正式" → formality=4 → 非简单 → critic 必跑），验证：
 * 1. 整图返回最终 {@link FashionState#RESULT}（FashionResult），coordinator 引用编号正确；
 * 2. 关键富类型（StylistOutput / CoordinatorOutput / FashionResult）经 Redis 序列化往返后仍可类型化读回
 *    （阶段 3 #1 风险：SpringAIJacksonStateSerializer 对自定义 record 的保型能力）；
 * 3. 用「全新 RedissonClient + 全新 CompiledGraph + 相同 threadId」能从 Redis 恢复出一致的 RESULT。
 *
 * <p>复用本地 Redis（127.0.0.1:6379，无密码）。命名带 {@code IntegrationTest} 后缀，由 unit profile
 * 排除（unit 不应依赖 Redis）、integration profile 纳入。
 */
class FashionGraphRedisRecoveryIntegrationTest {

    private static RedissonClient redisson() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        return Redisson.create(config);
    }

    /** 指向未监听端口、懒连接、零重试：模拟 Redis 缺失，用于验证图可退化为无 checkpoint 运行。 */
    private static RedissonClient unreachableRedisson() {
        Config config = new Config();
        config.setLazyInitialization(true);
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6399")
                .setDatabase(1)
                .setTimeout(500)
                .setConnectTimeout(500)
                .setRetryAttempts(0);
        return Redisson.create(config);
    }

    private static StylistOutput sampleStylist() {
        return new StylistOutput(List.of(new StylistOutput.OutfitSuggestion(
                1, "优雅浪漫风",
                new StylistOutput.Outfit("白衬衫", "半身裙", "小白鞋", "丝巾"),
                "白+粉", "理由", List.of("约会"), "均码", "002")));
    }

    /** 用 stub Bean 构造图运行所需的全部上下文（loop 关闭、影子模式，贴近默认配置）。 */
    private static FashionGraphContext mockContext() {
        QueryAnalyzer queryAnalyzer = mock(QueryAnalyzer.class);
        when(queryAnalyzer.analyze(anyString(), anyString()))
                .thenReturn(AnalyzedQuery.fallback("我要去海边婚礼穿什么，帮我搭一套正式的礼服"));

        FashionKnowledgeService knowledgeService = mock(FashionKnowledgeService.class);
        when(knowledgeService.retrieveExcluding(any(), any())).thenReturn(List.of());
        when(knowledgeService.formatContext(any())).thenReturn("rag context [outfit_002]");

        StylistAgent stylistAgent = mock(StylistAgent.class);
        when(stylistAgent.execute(any(), anyString(), any(), anyString())).thenReturn(sampleStylist());

        CriticAgent criticAgent = mock(CriticAgent.class);
        when(criticAgent.execute(any(), any())).thenReturn(new CriticOutput(List.of(
                new CriticOutput.Critique(1, 5, Map.of(), List.of(), List.of(), List.of(), List.of()))));

        TrendAgent trendAgent = mock(TrendAgent.class);
        when(trendAgent.execute(any(), any())).thenReturn(TrendOutput.neutral());

        CoordinatorAgent coordinatorAgent = mock(CoordinatorAgent.class);
        when(coordinatorAgent.execute(any(), any(), any(), any(), anyString()))
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

        return new FashionGraphContext(queryAnalyzer, knowledgeService, stylistAgent, criticAgent,
                trendAgent, coordinatorAgent, conversationService, userProfileService, embeddingService,
                referenceImageResolver, formatter, executor, new ObjectMapper(), false, 2, true,
                false, null, null, null);
    }

    /**
     * Redis 是可选依赖：Redis 不可达时 {@link FashionGraphRunner} 应跳过 checkpoint 正常编译并执行图，
     * 保证穿搭管道不因缓存/状态存储缺失而整条失效。
     */
    @Test
    void graphStillRunsWhenRedisUnavailable() throws Exception {
        RedissonClient unreachable = unreachableRedisson();
        try {
            FashionGraphRunner runner = new FashionGraphRunner(mockContext(), unreachable);

            Optional<FashionResult> result = runner.runForResult(
                    new FashionRequest("user-no-redis", "我要去海边婚礼穿什么，帮我搭一套正式的礼服"),
                    "no-redis-" + UUID.randomUUID());

            assertTrue(result.isPresent(), "Redis 不可用时图仍应产出结果（无 checkpoint 模式）");
            assertFalse(result.get().degraded(), "无 checkpoint 不应导致业务降级");
        } finally {
            unreachable.shutdown();
        }
    }

    /** 验证 Agent 轨迹接线：runForResult 应触发 run 开始、各节点 NODE step 与 run 结束。 */
    @Test
    void recordsTrajectoryNodeStepsForRun() throws Exception {
        CapturingRecorder recorder = new CapturingRecorder();
        RedissonClient unreachable = unreachableRedisson();
        try {
            FashionGraphRunner runner = new FashionGraphRunner(mockContext(), unreachable);
            runner.setTrajectoryRecorder(recorder);

            runner.runForResult(
                    new FashionRequest("traj-user", "我要去海边婚礼穿什么，帮我搭一套正式的礼服"),
                    "traj-" + UUID.randomUUID());

            List<String> nodes = recorder.nodeNames();
            assertTrue(nodes.contains("planner"), "应记录 planner 节点: " + nodes);
            assertTrue(nodes.contains("stylist"), "应记录 stylist 节点: " + nodes);
            assertTrue(nodes.contains("critic"), "应记录 critic 节点: " + nodes);
            assertTrue(nodes.contains("responder"), "应记录 responder 节点: " + nodes);
            assertEquals("SUCCESS", recorder.finishedStatus.get());
        } finally {
            unreachable.shutdown();
        }
    }

    /** 捕获轨迹写入的假记录器。 */
    private static final class CapturingRecorder implements AgentTrajectoryRecorder {
        final List<Step> steps = new ArrayList<>();
        final AtomicReference<String> finishedStatus = new AtomicReference<>();

        @Override
        public void startRun(RunStart start) {
        }

        @Override
        public void recordStep(Step step) {
            steps.add(step);
        }

        @Override
        public void finishRun(String runId, String status, long durationMs, String errorMessage) {
            finishedStatus.set(status);
        }

        List<String> nodeNames() {
            return steps.stream()
                    .filter(s -> Step.TYPE_NODE.equals(s.stepType()))
                    .map(Step::nodeName)
                    .toList();
        }
    }

    @Test
    void realGraphRunsDeepPathAndCheckpointRecoversRichResult() throws Exception {
        String threadId = "stage3-" + UUID.randomUUID();
        String query = "我要去海边婚礼穿什么，帮我搭一套正式的礼服";
        FashionRequest request = new FashionRequest("user1", query);
        ObjectMapper om = new ObjectMapper();
        RedissonClient rc1 = redisson();
        try {
            FashionGraphRunner runner1 = new FashionGraphRunner(mockContext(), rc1);

            // 含"婚礼/正式" → formality=4 → 非简单 → 走深路径（retrieve_memory→planner→rag→stylist→critic→responder）
            Optional<FashionResult> result = runner1.runForResult(request, threadId);

            assertTrue(result.isPresent(), "图应返回最终 FashionResult");
            FashionResult r = result.get();
            assertFalse(r.degraded(), "正常路径不应降级");
            assertNotNull(r.coordinator(), "应产出 CoordinatorOutput");
            assertEquals("002", r.coordinator().refinedOutfit().referenceOutfitId(),
                    "Coordinator 应引用 outfit 002");
            assertNotNull(r.stylist());
            assertEquals(1, r.stylist().suggestions().size());

            // 模拟「重启」：独立 RedissonClient + 独立 CompiledGraph，凭相同 threadId 从 Redis 恢复
            RedissonClient rc2 = redisson();
            try {
                FashionGraphRunner runner2 = new FashionGraphRunner(mockContext(), rc2);
                Optional<StateSnapshot> recovered = runner2.recover(threadId);

                assertTrue(recovered.isPresent(), "Redis 中应能恢复出 checkpoint");
                OverAllState recoveredState = recovered.get().state();
                FashionResult recResult = FashionState.readJson(
                        recoveredState, FashionState.RESULT, FashionResult.class, om).orElse(null);
                assertNotNull(recResult, "恢复的 RESULT 应可类型化读回（JSON 字符串保型）");
                assertEquals("002", recResult.coordinator().refinedOutfit().referenceOutfitId(),
                        "恢复的 RESULT 应与原图一致（重启可恢复）");
                assertEquals(r.stylist().suggestions().size(), recResult.stylist().suggestions().size());
            } finally {
                rc2.shutdown();
            }
        } finally {
            rc1.shutdown();
        }
    }
}
