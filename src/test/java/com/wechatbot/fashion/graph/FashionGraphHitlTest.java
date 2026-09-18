package com.wechatbot.fashion.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatbot.fashion.ai.fashion.look.FashionResponseFormatter;
import com.wechatbot.fashion.ai.fashion.look.agent.CoordinatorAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.CriticAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.StylistAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.TrendAgent;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionConversationService;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionEmbeddingService;
import com.wechatbot.fashion.ai.fashion.look.profile.UserProfileService;
import com.wechatbot.fashion.ai.fashion.look.rag.FashionKnowledgeService;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.common.fashion.ReferenceImageResolver;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HITL 暂停/恢复守门测试（无 Redis，走框架内存 checkpoint）：
 * 命中付费意图的请求应在 confirm 节点前暂停，用户确认后恢复出结果，取消则出取消兜底。
 */
class FashionGraphHitlTest {

    private static final String QUERY = "帮我生成一张试穿图";

    @Test
    void pausesBeforeConfirmThenResumesOnApproval() throws Exception {
        FashionGraphContext ctx = mockContext(true);
        FashionGraphRunner runner = new FashionGraphRunner(ctx, mock(RedissonClient.class));
        String threadId = "hitl-approve-" + UUID.randomUUID();

        Optional<FashionResult> paused = runner.runForResult(new FashionRequest("u1", QUERY), threadId);

        assertThat(paused).as("命中付费意图应在 confirm 前暂停，不产出结果").isEmpty();
        assertThat(runner.isPaused(threadId)).as("应处于等待确认状态").isTrue();

        Optional<FashionResult> resumed = runner.resumeForResult(threadId, true);

        assertThat(resumed).isPresent();
        assertThat(resumed.get().degraded()).isFalse();
        assertThat(runner.isPaused(threadId)).isFalse();
    }

    @Test
    void cancelProducesCancellationResult() throws Exception {
        FashionGraphContext ctx = mockContext(true);
        FashionGraphRunner runner = new FashionGraphRunner(ctx, mock(RedissonClient.class));
        String threadId = "hitl-reject-" + UUID.randomUUID();

        runner.runForResult(new FashionRequest("u2", QUERY), threadId);
        assertThat(runner.isPaused(threadId)).isTrue();

        Optional<FashionResult> resumed = runner.resumeForResult(threadId, false);

        assertThat(resumed).isPresent();
        assertThat(resumed.get().degraded()).isTrue();
        assertThat(resumed.get().errorMessage()).contains("取消");
    }

    @Test
    void hitlDisabledDoesNotPause() throws Exception {
        FashionGraphContext ctx = mockContext(false);
        FashionGraphRunner runner = new FashionGraphRunner(ctx, mock(RedissonClient.class));
        String threadId = "hitl-off-" + UUID.randomUUID();

        Optional<FashionResult> result = runner.runForResult(new FashionRequest("u3", QUERY), threadId);

        assertThat(result).isPresent();
        assertThat(runner.isPaused(threadId)).isFalse();
    }

    /** 构造 hitl 开关可控、其余依赖 stub 的图上下文。 */
    private static FashionGraphContext mockContext(boolean hitlEnabled) {
        QueryAnalyzer queryAnalyzer = mock(QueryAnalyzer.class);
        when(queryAnalyzer.analyze(anyString(), anyString()))
                .thenAnswer(inv -> AnalyzedQuery.fallback(inv.getArgument(0)));

        FashionKnowledgeService knowledge = mock(FashionKnowledgeService.class);
        when(knowledge.retrieveExcluding(any(), any())).thenReturn(List.of());
        when(knowledge.formatContext(any())).thenReturn("rag context [outfit_002]");

        StylistAgent stylist = mock(StylistAgent.class);
        when(stylist.execute(any(), anyString(), any(), anyString())).thenReturn(sampleStylist());

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
        FashionGraphContext ctx = new FashionGraphContext(queryAnalyzer, knowledge, stylist,
                mock(CriticAgent.class), mock(TrendAgent.class), mock(CoordinatorAgent.class),
                conversationService, userProfileService, embeddingService, referenceImageResolver,
                formatter, executor, new ObjectMapper(), false, 2, true, false, null, null, null);
        ctx.setHitlEnabled(hitlEnabled);
        return ctx;
    }

    private static StylistOutput sampleStylist() {
        return new StylistOutput(List.of(new StylistOutput.OutfitSuggestion(
                1, "优雅风",
                new StylistOutput.Outfit("白衬衫", "半身裙", "小白鞋", "丝巾"),
                "白+粉", "理由", List.of("日常"), "均码", "002")));
    }
}
