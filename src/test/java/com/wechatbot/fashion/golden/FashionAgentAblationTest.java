package com.wechatbot.fashion.golden;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatbot.fashion.ai.fashion.look.FashionResponseFormatter;
import com.wechatbot.fashion.ai.fashion.look.agent.CoordinatorAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.CriticAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.StylistAgent;
import com.wechatbot.fashion.ai.fashion.look.agent.TrendAgent;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CoordinatorOutput;
import com.wechatbot.fashion.ai.fashion.look.model.CriticOutput;
import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.fashion.look.model.TrendOutput;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionConversationService;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionEmbeddingService;
import com.wechatbot.fashion.ai.fashion.look.profile.UserProfileService;
import com.wechatbot.fashion.ai.fashion.look.rag.FashionKnowledgeService;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.common.fashion.ReferenceImageResolver;
import com.wechatbot.fashion.graph.FashionGraphContext;
import com.wechatbot.fashion.graph.FashionGraphDefinition;
import com.wechatbot.fashion.graph.FashionResultBuilders;
import com.wechatbot.fashion.graph.FashionState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Critic 消融（结构/成本侧，mock）：同一批用例分别在「简单路径（Stylist-only）」与「深路径
 * （Stylist+Critic+Trend+Coordinator）」下运行真实子图，量化**评审节点的调用次数与墙钟开销**。
 *
 * <p>生成质量（约束满足率 / 幻觉率 / 人工评分）无法用 mock 证明，见 live harness
 * {@code FashionAgentAblationLiveTest} 与 {@code docs/critic-ablation.md}。
 *
 * <p>结论（结构侧）：评审链路使每请求多 3 次 Agent 调用（critic/trend/coordinator），这正是
 * 简单请求默认跳过评审、以及 critic 回环默认关闭的成本依据。
 */
class FashionAgentAblationTest {

    private record Counts(AtomicInteger stylist, AtomicInteger critic, AtomicInteger trend, AtomicInteger coordinator) {
        static Counts fresh() {
            return new Counts(new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        }
    }

    private record Measurement(String config, int stylist, int critic, int trend, int coordinator,
                               boolean degraded, long wallMs) {
    }

    @Test
    void structuralCostDiffersBetweenSimpleAndDeepPaths() throws Exception {
        Measurement simple = runPath(false, "stylist-only（简单路径）");
        Measurement deep = runPath(true, "deep（stylist+critic+trend+coordinator）");

        writeReport(List.of(simple, deep));

        // 简单路径：只调 Stylist，不调评审与裁决
        assertThat(simple.stylist()).isEqualTo(1);
        assertThat(simple.critic()).isZero();
        assertThat(simple.trend()).isZero();
        assertThat(simple.coordinator()).isZero();

        // 深路径：Stylist + Critic + Trend + Coordinator 各一次
        assertThat(deep.stylist()).isEqualTo(1);
        assertThat(deep.critic()).isEqualTo(1);
        assertThat(deep.trend()).isEqualTo(1);
        assertThat(deep.coordinator()).isEqualTo(1);

        // 评审链路带来的额外 Agent 调用 = 3
        int simpleCalls = simple.stylist() + simple.critic() + simple.trend() + simple.coordinator();
        int deepCalls = deep.stylist() + deep.critic() + deep.trend() + deep.coordinator();
        assertThat(deepCalls - simpleCalls).as("评审链路额外调用数").isEqualTo(3);
        assertThat(deep.degraded()).isFalse();
    }

    private Measurement runPath(boolean deep, String label) throws Exception {
        Counts counts = Counts.fresh();
        FashionGraphContext ctx = mockContext(deep, counts);
        CompiledGraph graph = FashionGraphDefinition.build(ctx).compile(CompileConfig.builder().build());

        long start = System.nanoTime();
        OverAllState state = graph.invoke(
                FashionState.initialInputs("ablation-user", "我要去海边婚礼穿什么，帮我搭一套正式的礼服",
                        UUID.randomUUID().toString()),
                RunnableConfig.builder().threadId("ablation-" + label).build()).orElseThrow();
        long wallMs = (System.nanoTime() - start) / 1_000_000;

        Object resultJson = state.value(FashionState.RESULT).orElse(null);
        boolean degraded = resultJson instanceof String s && s.contains("\"degraded\":true");
        return new Measurement(label, counts.stylist().get(), counts.critic().get(), counts.trend().get(),
                counts.coordinator().get(), degraded, wallMs);
    }

    private static FashionGraphContext mockContext(boolean deep, Counts counts) {
        QueryAnalyzer queryAnalyzer = mock(QueryAnalyzer.class);
        when(queryAnalyzer.analyze(anyString(), anyString())).thenAnswer(inv -> {
            // deep=true → formality 4（非简单）；deep=false → formality 2（简单）
            return new AnalyzedQuery(inv.getArgument(0),
                    List.of(inv.getArgument(0), "场景"),
                    new AnalyzedQuery.QueryParams("FORMAL_EVENT", "SUMMER", deep ? 4 : 2, "unknown", "优雅"), null);
        });

        FashionKnowledgeService knowledge = mock(FashionKnowledgeService.class);
        when(knowledge.retrieveExcluding(any(), any())).thenReturn(List.of());
        when(knowledge.formatContext(any())).thenReturn("rag context [outfit_002]");

        StylistAgent stylist = mock(StylistAgent.class);
        when(stylist.execute(any(), anyString(), any(), anyString())).thenAnswer(inv -> {
            counts.stylist().incrementAndGet();
            return sampleStylist();
        });

        CriticAgent critic = mock(CriticAgent.class);
        when(critic.execute(any(), any())).thenAnswer(inv -> {
            counts.critic().incrementAndGet();
            return new CriticOutput(List.of(
                    new CriticOutput.Critique(1, 5, Map.of(), List.of(), List.of(), List.of(), List.of())));
        });

        TrendAgent trend = mock(TrendAgent.class);
        when(trend.execute(any(), any())).thenAnswer(inv -> {
            counts.trend().incrementAndGet();
            return TrendOutput.neutral();
        });

        CoordinatorAgent coordinator = mock(CoordinatorAgent.class);
        when(coordinator.execute(any(), any(), any(), any(), anyString())).thenAnswer(inv -> {
            counts.coordinator().incrementAndGet();
            return FashionResultBuilders.fromStylist(sampleStylist());
        });

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
        return new FashionGraphContext(queryAnalyzer, knowledge, stylist, critic, trend, coordinator,
                conversationService, userProfileService, embeddingService, referenceImageResolver,
                formatter, executor, new ObjectMapper(), false, 2, true, false, null, null, null);
    }

    private static StylistOutput sampleStylist() {
        return new StylistOutput(List.of(new StylistOutput.OutfitSuggestion(
                1, "优雅风", new StylistOutput.Outfit("白衬衫", "半身裙", "小白鞋", "丝巾"),
                "白+粉", "理由", List.of("婚礼"), "均码", "002")));
    }

    private static void writeReport(List<Measurement> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Critic 消融（结构/成本侧，mock）\n\n");
        sb.append("> 同一 query，只切换 planner 判定（简单/深）。mock 下时延为图编排开销，非真实模型时延。\n\n");
        sb.append("| 配置 | Stylist | Critic | Trend | Coordinator | 合计 Agent 调用 | 降级 | 墙钟 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Measurement m : rows) {
            int total = m.stylist() + m.critic() + m.trend() + m.coordinator();
            sb.append("| ").append(m.config())
                    .append(" | ").append(m.stylist())
                    .append(" | ").append(m.critic())
                    .append(" | ").append(m.trend())
                    .append(" | ").append(m.coordinator())
                    .append(" | ").append(total)
                    .append(" | ").append(m.degraded())
                    .append(" | ").append(m.wallMs()).append("ms |\n");
        }
        Path out = Path.of("target", "golden", "critic-ablation-structural.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println(sb);
    }
}
