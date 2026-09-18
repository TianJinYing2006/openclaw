package com.wechatbot.fashion.ai.orchestration;

import com.wechatbot.fashion.ai.config.AgentBudgetProperties;
import com.wechatbot.fashion.graph.budget.RunBudgetTracker;
import com.wechatbot.fashion.graph.trajectory.AgentTrajectoryRecorder;
import com.wechatbot.fashion.graph.trajectory.TrajectoryRunContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 工具治理执行层：预算拒绝、超时、审计与输入脱敏。 */
class GovernedToolCallbackTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void clear() {
        TrajectoryRunContext.clear();
        AgentSessionContext.clear();
        executor.shutdownNow();
    }

    @Test
    void propagatesSessionContextToTimeoutExecutor() {
        AgentSessionContext.set("user-ctx", "sess-ctx");
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        GovernedToolCallback governed = new GovernedToolCallback(
                delegate("fashion_consultant", () -> {
                    seen.set(AgentSessionContext.currentUserId());
                    return "ok";
                }),
                new ToolPolicy(ToolRisk.READ_ONLY, 0, false, 1000, 0),
                null, null, executor);

        governed.call("{}");

        assertThat(seen.get()).as("工具在超时执行器线程上仍能看到会话上下文").isEqualTo("user-ctx");
    }

    private static ToolCallback delegate(String name, java.util.function.Supplier<String> body) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> body.get());
        return callback;
    }

    private static final class CapturingRecorder implements AgentTrajectoryRecorder {
        final List<Step> steps = new ArrayList<>();

        @Override
        public void startRun(RunStart start) {
        }

        @Override
        public void recordStep(Step step) {
            steps.add(step);
        }

        @Override
        public void finishRun(String runId, String status, long durationMs, String errorMessage) {
        }
    }

    @Test
    void deniesCallWhenRunToolBudgetExhausted() {
        AgentBudgetProperties props = new AgentBudgetProperties();
        props.setEnabled(true);
        props.setMaxToolCallsPerRun(1);
        RunBudgetTracker tracker = new RunBudgetTracker(props);
        tracker.begin("run-x");
        TrajectoryRunContext.set("run-x", "chat");

        int[] invoked = {0};
        GovernedToolCallback governed = new GovernedToolCallback(
                delegate("search_web", () -> {
                    invoked[0]++;
                    return "ok";
                }),
                new ToolPolicy(ToolRisk.READ_ONLY, 0, false, 1000, 20),
                tracker, null, executor);

        assertThat(governed.call("{\"q\":\"a\"}")).isEqualTo("ok");
        String second = governed.call("{\"q\":\"b\"}");

        assertThat(second).contains("拒绝");
        assertThat(invoked[0]).as("超预算时不应触达真实工具").isEqualTo(1);
    }

    @Test
    void timesOutSlowToolWithoutBlocking() {
        GovernedToolCallback governed = new GovernedToolCallback(
                delegate("slow_tool", () -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "late";
                }),
                new ToolPolicy(ToolRisk.READ_ONLY, 0, false, 50, 0),
                null, null, executor);

        long start = System.nanoTime();
        String result = governed.call("{}");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).contains("超时");
        assertThat(elapsedMs).isLessThan(1500);
    }

    @Test
    void recordsAuditStepWithRedactedInput() {
        CapturingRecorder recorder = new CapturingRecorder();
        TrajectoryRunContext.set("run-audit", "tool_loop");

        GovernedToolCallback governed = new GovernedToolCallback(
                delegate("get_current_weather", () -> "sunny"),
                new ToolPolicy(ToolRisk.READ_ONLY, 0, false, 1000, 0),
                null, recorder, executor);

        governed.call("{\"city\":\"杭州\",\"secret\":\"abc\"}");

        assertThat(recorder.steps).hasSize(1);
        AgentTrajectoryRecorder.Step step = recorder.steps.get(0);
        assertThat(step.stepType()).isEqualTo(AgentTrajectoryRecorder.Step.TYPE_TOOL);
        assertThat(step.toolName()).isEqualTo("get_current_weather");
        assertThat(step.status()).isEqualTo("SUCCESS");
        assertThat(step.inputSummary()).startsWith("sha256=").doesNotContain("杭州");
    }

    @Test
    void confirmationGateExecutesOnceThenReplays() {
        org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        com.wechatbot.fashion.graph.hitl.ConfirmationService confirmations =
                new com.wechatbot.fashion.graph.hitl.ConfirmationService(
                        new com.wechatbot.fashion.graph.hitl.JdbcConfirmationStore(provider));
        AgentSessionContext.set("user-hitl", "s");

        java.util.concurrent.atomic.AtomicInteger invoked = new java.util.concurrent.atomic.AtomicInteger();
        GovernedToolCallback governed = new GovernedToolCallback(
                delegate("virtual_try_on_reference_outfit", () -> {
                    invoked.incrementAndGet();
                    return "试穿任务已提交，编号：abc";
                }),
                new ToolPolicy(ToolRisk.PAID_OPERATION, 0, true, 1000, 5),
                null, null, executor, confirmations);

        String first = governed.call("\"153\"");
        assertThat(first).contains("确认");
        assertThat(invoked.get()).as("未确认前不应执行").isZero();

        com.wechatbot.fashion.graph.hitl.ConfirmationRecord rec = confirmations
                .latest("user-hitl", "virtual_try_on_reference_outfit").orElseThrow();
        confirmations.markResolved(rec, true, "");

        String second = governed.call("\"153\"");
        assertThat(second).contains("试穿任务已提交");
        assertThat(invoked.get()).isEqualTo(1);

        String third = governed.call("\"153\"");
        assertThat(third).contains("试穿任务已提交");
        assertThat(invoked.get()).as("已消费后应重放结果、不重复执行").isEqualTo(1);
    }

    @Test
    void recordsFailureWhenToolReturnsFailureText() {
        CapturingRecorder recorder = new CapturingRecorder();
        TrajectoryRunContext.set("run-fail", "chat");

        GovernedToolCallback governed = new GovernedToolCallback(
                delegate("fashion_consultant", () -> "抱歉，穿搭推荐服务暂时遇到了问题，请稍后再试。"),
                new ToolPolicy(ToolRisk.READ_ONLY, 0, false, 1000, 0),
                null, recorder, executor);

        governed.call("{}");

        assertThat(recorder.steps).hasSize(1);
        assertThat(recorder.steps.get(0).status()).isEqualTo("FAILURE");
        assertThat(recorder.steps.get(0).errorMessage()).isEqualTo("tool-returned-failure-text");
    }

    @Test
    void redactedInputNeverExposesRawArguments() {
        String redacted = GovernedToolCallback.redactedInput("{\"token\":\"sk-secret\"}");
        assertThat(redacted).doesNotContain("sk-secret").contains("len=");
    }

    @Test
    void worksWithoutRunContextAndGovernanceComponents() {
        GovernedToolCallback governed = new GovernedToolCallback(
                delegate("noop", () -> "fine"),
                new ToolPolicy(ToolRisk.READ_ONLY, 0, false, 1000, 0),
                null, null, executor);
        assertThat(governed.call("{}")).isEqualTo("fine");
    }
}
