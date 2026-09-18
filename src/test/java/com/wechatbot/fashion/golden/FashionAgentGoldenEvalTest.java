package com.wechatbot.fashion.golden;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.wechatbot.fashion.ai.fashion.look.FashionResponseFormatter;
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
import com.wechatbot.fashion.graph.FashionGraphContext;
import com.wechatbot.fashion.graph.FashionGraphDefinition;
import com.wechatbot.fashion.graph.FashionResultBuilders;
import com.wechatbot.fashion.graph.FashionState;
import com.wechatbot.fashion.graph.nodes.ToolLoopNode;
import com.wechatbot.fashion.graph.trajectory.AgentTrajectoryRecorder;
import com.wechatbot.fashion.graph.trajectory.TrajectoryLifecycleListener;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 穿搭 Agent 确定性 Golden 评测（mock LLM）。
 *
 * <p>数据集：{@code src/test/resources/golden/fashion_agent_golden.json}。每条用例喂入脚本化的
 * QueryAnalyzer 计划 / 评审分数 / 故障注入，运行**真实 Fashion 子图**（真实路由、终止、降级逻辑），
 * 断言：节点路由序列、工具门控、最终状态、降级恢复、Coordinator 有无。
 *
 * <p>指标：routing / toolGate / completion / status / degraded / recovery 准确率 + 延迟 p50/p95
 * （mock 下为图编排开销，非真实模型时延；Token 成本需 live 集）。全部用例必须通过（确定性），否则失败。
 */
class FashionAgentGoldenEvalTest {

    // ---- 数据集结构 ----
    record Plan(String scene, String season, int formality, List<String> subQueries, String styleHint) {
        AnalyzedQuery toAnalyzedQuery(String query) {
            return new AnalyzedQuery(query, subQueries,
                    new AnalyzedQuery.QueryParams(scene, season, formality, "unknown", styleHint), null);
        }
    }

    record Expect(boolean needsTool, List<String> nodesVisited, String finalStatus,
                  boolean degraded, Boolean coordinatorPresent) {
    }

    record Case(String id, String query, Plan plan, String fault, Integer criticScore,
                Boolean loopEnabled, Expect expect) {
    }

    record Dataset(String version, String description, List<Case> cases) {
    }

    /** 捕获轨迹写入的假记录器。 */
    private static final class CapturingRecorder implements AgentTrajectoryRecorder {
        final List<Step> steps = new ArrayList<>();
        final AtomicReference<String> finishedStatus = new AtomicReference<>();
        final AtomicReference<Long> runDurationMs = new AtomicReference<>(0L);

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
            runDurationMs.set(durationMs);
        }

        List<String> nodeNames() {
            return steps.stream()
                    .filter(s -> Step.TYPE_NODE.equals(s.stepType()))
                    .map(Step::nodeName)
                    .toList();
        }
    }

    private record CaseResult(String id, boolean routingOk, boolean toolOk, boolean completionOk,
                              boolean statusOk, boolean degradedOk, boolean coordinatorOk,
                              long durationMs, String detail) {
        boolean allOk() {
            return routingOk && toolOk && completionOk && statusOk && degradedOk && coordinatorOk;
        }
    }

    @Test
    void goldenCasesMeetRoutingToolTerminationAndRecoveryExpectations() throws Exception {
        Dataset dataset = loadDataset();
        assertThat(dataset.cases()).isNotEmpty();

        List<CaseResult> results = new ArrayList<>();
        for (Case c : dataset.cases()) {
            results.add(evaluate(c));
        }

        writeReport(dataset, results);

        List<String> failed = results.stream()
                .filter(r -> !r.allOk())
                .map(r -> r.id() + " -> " + r.detail())
                .toList();
        assertThat(failed)
                .as("Golden 用例失败（意图/路由/降级偏离预期）")
                .isEmpty();
    }

    private CaseResult evaluate(Case c) throws Exception {
        Rigs rigs = buildRigs(c);
        CapturingRecorder recorder = new CapturingRecorder();
        CompiledGraph graph = FashionGraphDefinition.build(rigs.ctx())
                .compile(CompileConfig.builder()
                        .withLifecycleListener(new TrajectoryLifecycleListener(recorder))
                        .build());

        long start = System.nanoTime();
        Optional<OverAllState> state = graph.invoke(
                FashionState.initialInputs("golden-" + c.id(), c.query(), UUID.randomUUID().toString()),
                RunnableConfig.builder().threadId("golden-" + c.id()).build());
        long wallMs = (System.nanoTime() - start) / 1_000_000;

        FashionResult result = state
                .flatMap(s -> FashionState.readJson(s, FashionState.RESULT, FashionResult.class, new ObjectMapper()))
                .orElse(null);

        List<String> actualNodes = recorder.nodeNames();
        boolean routingOk = actualNodes.equals(c.expect().nodesVisited());
        boolean toolOk = ToolLoopNode.needsTool(c.query()) == c.expect().needsTool();
        boolean completionOk = result != null;
        boolean statusOk = c.expect().finalStatus().equals(recorder.finishedStatus.get());
        boolean degradedOk = result != null && result.degraded() == c.expect().degraded();
        boolean coordinatorOk = c.expect().coordinatorPresent() == null
                || (result != null && (result.coordinator() != null) == c.expect().coordinatorPresent());

        StringBuilder detail = new StringBuilder();
        if (!routingOk) {
            detail.append("nodes=").append(actualNodes).append(" expected=").append(c.expect().nodesVisited()).append("; ");
        }
        if (!toolOk) {
            detail.append("needsTool=").append(ToolLoopNode.needsTool(c.query())).append("; ");
        }
        if (!statusOk) {
            detail.append("status=").append(recorder.finishedStatus.get()).append("; ");
        }
        if (!degradedOk) {
            detail.append("degraded=").append(result != null && result.degraded()).append("; ");
        }
        if (!coordinatorOk) {
            detail.append("coordinator=").append(result != null && result.coordinator() != null).append("; ");
        }
        return new CaseResult(c.id(), routingOk, toolOk, completionOk, statusOk, degradedOk, coordinatorOk,
                wallMs, detail.toString());
    }

    private static Rigs buildRigs(Case c) {
        QueryAnalyzer queryAnalyzer = mock(QueryAnalyzer.class);
        when(queryAnalyzer.analyze(anyString(), anyString())).thenAnswer(inv -> c.plan().toAnalyzedQuery(inv.getArgument(0)));

        FashionKnowledgeService knowledge = mock(FashionKnowledgeService.class);
        if ("rag".equals(c.fault())) {
            when(knowledge.retrieveExcluding(any(), any())).thenThrow(new RuntimeException("RAGFlow 宕机"));
        } else {
            when(knowledge.retrieveExcluding(any(), any())).thenReturn(List.of());
        }
        when(knowledge.formatContext(any())).thenReturn("rag context [outfit_002]");

        StylistAgent stylist = mock(StylistAgent.class);
        if ("stylist".equals(c.fault())) {
            doThrow(new RuntimeException("stylist down")).when(stylist).execute(any(), anyString(), any(), anyString());
        } else {
            when(stylist.execute(any(), anyString(), any(), anyString())).thenReturn(sampleStylist());
        }

        CriticAgent critic = mock(CriticAgent.class);
        if ("critic".equals(c.fault()) || "critic_and_trend".equals(c.fault())) {
            doThrow(new RuntimeException("critic down")).when(critic).execute(any(), any());
        } else {
            int score = c.criticScore() == null ? 5 : c.criticScore();
            when(critic.execute(any(), any())).thenReturn(new CriticOutput(List.of(
                    new CriticOutput.Critique(1, score, Map.of(), List.of(), List.of(), List.of(), List.of()))));
        }

        TrendAgent trend = mock(TrendAgent.class);
        if ("trend".equals(c.fault()) || "critic_and_trend".equals(c.fault())) {
            doThrow(new RuntimeException("trend down")).when(trend).execute(any(), any());
        } else {
            when(trend.execute(any(), any())).thenReturn(TrendOutput.neutral());
        }

        CoordinatorAgent coordinator = mock(CoordinatorAgent.class);
        if ("coordinator".equals(c.fault())) {
            doThrow(new RuntimeException("coordinator timed out")).when(coordinator)
                    .execute(any(), any(), any(), any(), anyString());
        } else {
            when(coordinator.execute(any(), any(), any(), any(), anyString()))
                    .thenReturn(FashionResultBuilders.fromStylist(sampleStylist()));
        }

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
        boolean loopEnabled = Boolean.TRUE.equals(c.loopEnabled());
        FashionGraphContext ctx = new FashionGraphContext(queryAnalyzer, knowledge, stylist, critic, trend,
                coordinator, conversationService, userProfileService, embeddingService, referenceImageResolver,
                formatter, executor, new ObjectMapper(), loopEnabled, 2, true, false, null, null, null);
        return new Rigs(ctx);
    }

    private record Rigs(FashionGraphContext ctx) {
    }

    private static StylistOutput sampleStylist() {
        return new StylistOutput(List.of(new StylistOutput.OutfitSuggestion(
                1, "优雅浪漫风",
                new StylistOutput.Outfit("白衬衫", "半身裙", "小白鞋", "丝巾"),
                "白+粉", "理由", List.of("婚礼"), "均码", "002")));
    }

    private static Dataset loadDataset() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = FashionAgentGoldenEvalTest.class
                .getResourceAsStream("/golden/fashion_agent_golden.json")) {
            assertThat(in).as("golden 数据集必须存在").isNotNull();
            return mapper.readValue(in, new TypeReference<Dataset>() {
            });
        }
    }

    /** 输出可存档的评测报告（target/golden/）。 */
    private static void writeReport(Dataset dataset, List<CaseResult> results) throws Exception {
        long total = results.size();
        long routing = results.stream().filter(CaseResult::routingOk).count();
        long tool = results.stream().filter(CaseResult::toolOk).count();
        long completion = results.stream().filter(CaseResult::completionOk).count();
        long status = results.stream().filter(CaseResult::statusOk).count();
        long degraded = results.stream().filter(CaseResult::degradedOk).count();
        long coordinator = results.stream().filter(CaseResult::coordinatorOk).count();
        List<Long> durations = results.stream().map(CaseResult::durationMs).sorted().toList();
        long p50 = percentile(durations, 0.50);
        long p95 = percentile(durations, 0.95);

        StringBuilder sb = new StringBuilder();
        sb.append("# Fashion Agent Golden 评测报告（mock）\n\n");
        sb.append("- 数据集版本：").append(dataset.version()).append("\n");
        sb.append("- 用例数：").append(total).append("\n");
        sb.append("- routing 准确率：").append(pct(routing, total)).append("\n");
        sb.append("- 工具门控准确率：").append(pct(tool, total)).append("\n");
        sb.append("- 任务完成率：").append(pct(completion, total)).append("\n");
        sb.append("- 最终状态准确率：").append(pct(status, total)).append("\n");
        sb.append("- 降级准确率：").append(pct(degraded, total)).append("\n");
        sb.append("- Coordinator 有无准确率：").append(pct(coordinator, total)).append("\n");
        sb.append("- 墙钟 p50 / p95（mock，含图编排开销）：").append(p50).append("ms / ").append(p95).append("ms\n\n");
        sb.append("| 用例 | 路由 | 工具门控 | 完成 | 状态 | 降级 | Coordinator | 耗时 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (CaseResult r : results) {
            sb.append("| ").append(r.id())
                    .append(" | ").append(yes(r.routingOk()))
                    .append(" | ").append(yes(r.toolOk()))
                    .append(" | ").append(yes(r.completionOk()))
                    .append(" | ").append(yes(r.statusOk()))
                    .append(" | ").append(yes(r.degradedOk()))
                    .append(" | ").append(yes(r.coordinatorOk()))
                    .append(" | ").append(r.durationMs()).append("ms |\n");
        }
        Path out = Path.of("target", "golden", "fashion-agent-golden-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println(sb);
    }

    private static String yes(boolean ok) {
        return ok ? "PASS" : "FAIL";
    }

    private static String pct(long ok, long total) {
        return total == 0 ? "n/a" : String.format("%.1f%% (%d/%d)", 100.0 * ok / total, ok, total);
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
