package com.wechatbot.fashion.graph.trajectory;

import com.wechatbot.fashion.graph.FashionState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 轨迹单元测试：上下文传播、JDBC 记录器（含降级/截断）、生命周期监听器（run + NODE + 状态）。
 */
class AgentTrajectoryTest {

    /** 捕获所有写入的假记录器。 */
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
    }

    @Test
    void runContextIsThreadLocalAndClears() {
        TrajectoryRunContext.set("run-1", "stylist");
        assertThat(TrajectoryRunContext.current().runId()).isEqualTo("run-1");
        assertThat(TrajectoryRunContext.current().node()).isEqualTo("stylist");

        TrajectoryRunContext.clear();
        assertThat(TrajectoryRunContext.current().present()).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void jdbcRecorderWritesRunAndStepsAndAggregatesOnFinish() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        JdbcAgentTrajectoryRecorder recorder = new JdbcAgentTrajectoryRecorder(provider);

        recorder.startRun(new AgentTrajectoryRecorder.RunStart(
                "run-1", "thread-1", "user-1", "海边穿什么", "gv1", "pv1", "qwen"));
        recorder.recordStep(step("run-1", "stylist", AgentTrajectoryRecorder.Step.TYPE_MODEL, 120));
        recorder.recordStep(step("run-1", "stylist", AgentTrajectoryRecorder.Step.TYPE_MODEL, 80));
        recorder.finishRun("run-1", "SUCCESS", 500, null);

        // 1 次 startRun INSERT + 2 次 step INSERT + 1 次 finish UPDATE
        verify(jdbc, times(4)).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void jdbcRecorderIsNoopWithoutJdbc() {
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        JdbcAgentTrajectoryRecorder recorder = new JdbcAgentTrajectoryRecorder(provider);

        recorder.startRun(new AgentTrajectoryRecorder.RunStart(
                "run-1", "t", "u", "q", "g", "p", "m"));
        recorder.recordStep(step("run-1", "stylist", AgentTrajectoryRecorder.Step.TYPE_MODEL, 10));
        recorder.finishRun("run-1", "SUCCESS", 10, null);

        assertThat(JdbcAgentTrajectoryRecorder.truncate("a\nb", 10)).isEqualTo("a b");
        assertThat(JdbcAgentTrajectoryRecorder.truncate("abcdef", 3)).isEqualTo("abc");
        assertThat(JdbcAgentTrajectoryRecorder.truncate(null, 3)).isEmpty();
    }

    @Test
    void lifecycleListenerRecordsRunNodeAndSuccessStatus() {
        CapturingRecorder recorder = new CapturingRecorder();
        TrajectoryLifecycleListener listener = new TrajectoryLifecycleListener(recorder);
        Map<String, Object> state = new HashMap<>();
        state.put(FashionState.RUN_ID, "run-1");
        state.put(FashionState.USER_ID, "user-1");
        state.put(FashionState.QUERY, "海边婚礼穿什么");
        state.put(FashionState.RESULT, "{\"degraded\":false}");

        listener.onStart("exec-1", state, null);
        listener.before("stylist", state, null, null);
        listener.after("stylist", state, null, null);
        listener.onComplete("exec-1", state, null);

        assertThat(recorder.steps).hasSize(1);
        assertThat(recorder.steps.get(0).nodeName()).isEqualTo("stylist");
        assertThat(recorder.steps.get(0).stepType()).isEqualTo(AgentTrajectoryRecorder.Step.TYPE_NODE);
        assertThat(recorder.finishedStatus.get()).isEqualTo("SUCCESS");
    }

    @Test
    void lifecycleListenerMarksDegradedRun() {
        CapturingRecorder recorder = new CapturingRecorder();
        TrajectoryLifecycleListener listener = new TrajectoryLifecycleListener(recorder);
        Map<String, Object> state = new HashMap<>();
        state.put(FashionState.RUN_ID, "run-2");
        state.put(FashionState.RESULT, "{\"degraded\":true,\"success\":true}");

        listener.onStart("exec-2", state, null);
        listener.onComplete("exec-2", state, null);

        assertThat(recorder.finishedStatus.get()).isEqualTo("DEGRADED");
    }

    @Test
    void nullRecorderFallsBackToNoop() {
        TrajectoryLifecycleListener listener = new TrajectoryLifecycleListener(null);
        // 不应抛异常；未设置 runId 的 state 直接跳过。
        listener.onStart("exec", new HashMap<>(), null);
        listener.before("planner", new HashMap<>(), null, null);
        listener.after("planner", new HashMap<>(), null, null);
    }

    private static AgentTrajectoryRecorder.Step step(String runId, String node, String type, long duration) {
        return new AgentTrajectoryRecorder.Step(
                runId, node, type, AgentTrajectoryRecorder.Step.STATUS_SUCCESS,
                "Map", null, "in", "out", 1, 2, 3, duration, null);
    }
}
