package com.wechatbot.fashion.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatbot.fashion.ai.fashion.look.FashionResponseFormatter;
import com.wechatbot.fashion.ai.fashion.look.agent.CoordinatorAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.CriticAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.StylistAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.TrendAgent;
import com.wechatbot.fashion.ai.fashion.look.model.CriticOutput;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionConversationService;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionEmbeddingService;
import com.wechatbot.fashion.ai.fashion.look.profile.UserProfileService;
import com.wechatbot.fashion.ai.fashion.look.rag.FashionKnowledgeService;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.common.fashion.ReferenceImageResolver;
import com.wechatbot.fashion.graph.FashionGraphContext;
import com.wechatbot.fashion.graph.FashionState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Critic 节点级 deadline：Critic∥Trend 任一卡住时，节点应在 deadline 内降级返回，不阻塞整图。
 */
class CriticNodeDeadlineTest {

    @Test
    void returnsWithinDeadlineWhenReviewAgentsHang() throws Exception {
        CriticAgent critic = mock(CriticAgent.class);
        when(critic.execute(any(), any())).thenAnswer(inv -> {
            sleepQuietly(1000);
            return new CriticOutput(List.of());
        });
        TrendAgent trend = mock(TrendAgent.class);
        when(trend.execute(any(), any())).thenAnswer(inv -> {
            sleepQuietly(1000);
            return com.wechatbot.fashion.ai.fashion.look.model.TrendOutput.neutral();
        });

        FashionGraphContext ctx = mockContext(critic, trend);
        ctx.setCriticDeadlineMillis(100);

        CriticNode node = new CriticNode(ctx);
        long start = System.nanoTime();
        Map<String, Object> out = node.apply(new OverAllState(new HashMap<>()))
                .get(2, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("应在 deadline 附近返回而非等满 1s").isLessThan(700);
        assertThat(out).containsKey(FashionState.CRITIC_VERDICT);
        assertThat(out.get(FashionState.CRITIC_VERDICT)).isEqualTo("reject");
    }

    private static FashionGraphContext mockContext(CriticAgent critic, TrendAgent trend) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        return new FashionGraphContext(
                mock(QueryAnalyzer.class), mock(FashionKnowledgeService.class), mock(StylistAgent.class),
                critic, trend, mock(CoordinatorAgent.class), mock(FashionConversationService.class),
                mock(UserProfileService.class), mock(FashionEmbeddingService.class),
                mock(ReferenceImageResolver.class), mock(FashionResponseFormatter.class),
                executor, new ObjectMapper(), false, 2, true, false, null, null, null);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
