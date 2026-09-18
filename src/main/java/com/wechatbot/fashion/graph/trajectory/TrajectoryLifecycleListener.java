package com.wechatbot.fashion.graph.trajectory;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.wechatbot.fashion.graph.FashionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 spring-ai-alibaba-graph 的生命周期回调转成 {@link AgentTrajectoryRecorder} 的 run / NODE step。
 *
 * <p>记录内容：
 * <ul>
 *   <li>{@code onStart} → {@link AgentTrajectoryRecorder#startRun}（runId 来自 state）；</li>
 *   <li>{@code before}/{@code after} → NODE step（节点名 + 耗时 + 状态）；</li>
 *   <li>{@code onError} → FAILED NODE step + 结束 run；</li>
 *   <li>{@code onComplete} → 结束 run（按 RESULT 是否 degraded 推导状态）。</li>
 * </ul>
 *
 * <p>节点耗时用本类自己的 nanoTime 记录（不依赖框架传入的 Long 语义），按 executionId+节点名
 * 存放；同一 run 内节点串行执行，重入（critic 回边）会覆盖旧值，属可接受近似。
 */
public class TrajectoryLifecycleListener implements GraphLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryLifecycleListener.class);

    /** 图结构版本：节点/边拓扑变更时递增，用于轨迹 A/B 对比。 */
    public static final String GRAPH_VERSION = "fashion-graph-v1";
    /** Prompt 契约版本：AgentPrompts 变更时递增，用于质量回溯。 */
    public static final String PROMPT_VERSION = "agent-prompts-v1";

    private final AgentTrajectoryRecorder recorder;
    private final com.wechatbot.fashion.graph.budget.RunBudgetTracker budgetTracker;
    private final ConcurrentHashMap<String, Long> runStartedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> nodeStartedAt = new ConcurrentHashMap<>();
    private final Set<String> finishedRuns = ConcurrentHashMap.newKeySet();

    public TrajectoryLifecycleListener(AgentTrajectoryRecorder recorder) {
        this(recorder, null);
    }

    public TrajectoryLifecycleListener(AgentTrajectoryRecorder recorder,
                                       com.wechatbot.fashion.graph.budget.RunBudgetTracker budgetTracker) {
        this.recorder = recorder == null ? AgentTrajectoryRecorder.noop() : recorder;
        this.budgetTracker = budgetTracker;
    }

    @Override
    public void onStart(String executionId, Map<String, Object> state, RunnableConfig config) {
        String runId = stringValue(state, FashionState.RUN_ID);
        if (runId == null) {
            return;
        }
        runStartedAt.put(key(executionId, runId), System.nanoTime());
        if (budgetTracker != null) {
            budgetTracker.begin(runId);
        }
        recorder.startRun(new AgentTrajectoryRecorder.RunStart(
                runId,
                stringValue(state, FashionState.USER_ID),
                stringValue(state, FashionState.USER_ID),
                stringValue(state, FashionState.QUERY),
                GRAPH_VERSION,
                PROMPT_VERSION,
                null));
    }

    @Override
    public void before(String nodeName, Map<String, Object> state, RunnableConfig config, Long timestamp) {
        nodeStartedAt.put(nodeKey(state, nodeName), System.nanoTime());
    }

    @Override
    public void after(String nodeName, Map<String, Object> state, RunnableConfig config, Long timestamp) {
        String runId = stringValue(state, FashionState.RUN_ID);
        if (runId == null) {
            return;
        }
        Long started = nodeStartedAt.remove(nodeKey(state, nodeName));
        long durationMs = started == null ? 0 : (System.nanoTime() - started) / 1_000_000;
        recorder.recordStep(new AgentTrajectoryRecorder.Step(
                runId, nodeName, AgentTrajectoryRecorder.Step.TYPE_NODE,
                AgentTrajectoryRecorder.Step.STATUS_SUCCESS,
                null, null, null, nodeOutputSummary(nodeName, state),
                0, 0, 0, durationMs, null));
    }

    @Override
    public void onError(String nodeName, Map<String, Object> state, Throwable throwable, RunnableConfig config) {
        String runId = stringValue(state, FashionState.RUN_ID);
        if (runId == null) {
            return;
        }
        String message = throwable == null ? "unknown" : throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        recorder.recordStep(new AgentTrajectoryRecorder.Step(
                runId, nodeName, AgentTrajectoryRecorder.Step.TYPE_NODE,
                AgentTrajectoryRecorder.Step.STATUS_FAILED,
                null, null, null, null, 0, 0, 0, 0, message));
        finishRunOnce(runId, "FAILED", 0, message);
    }

    @Override
    public void onComplete(String executionId, Map<String, Object> state, RunnableConfig config) {
        String runId = stringValue(state, FashionState.RUN_ID);
        if (runId == null) {
            return;
        }
        Long started = runStartedAt.remove(key(executionId, runId));
        long durationMs = started == null ? 0 : (System.nanoTime() - started) / 1_000_000;
        finishRunOnce(runId, statusFromResult(state), durationMs, null);
        nodeStartedAt.keySet().removeIf(k -> k.startsWith(runId + "\u0000"));
    }

    private void finishRunOnce(String runId, String status, long durationMs, String error) {
        if (!finishedRuns.add(runId)) {
            return;
        }
        recorder.finishRun(runId, status, durationMs, error);
        if (budgetTracker != null) {
            budgetTracker.finish(runId);
        }
    }

    private static String statusFromResult(Map<String, Object> state) {
        Object result = state == null ? null : state.get(FashionState.RESULT);
        if (result instanceof String json && json.contains("\"degraded\":true")) {
            return "DEGRADED";
        }
        return "SUCCESS";
    }

    /** 节点的轻量产出摘要（供回放页一眼看清每步结果），不落敏感原文。 */
    private static String nodeOutputSummary(String nodeName, Map<String, Object> state) {
        if (state == null) {
            return "";
        }
        return switch (nodeName) {
            case "planner" -> "simple=" + state.getOrDefault(FashionState.SIMPLE, false)
                    + ", conversationId=" + state.getOrDefault(FashionState.CONVERSATION_ID, "-");
            case "rag" -> {
                Object ctx = state.get(FashionState.RAG_CONTEXT);
                yield "ragChars=" + (ctx instanceof String s ? s.length() : 0);
            }
            case "tool_loop" -> {
                Object ctx = state.get(FashionState.TOOL_CONTEXT);
                yield "toolContextChars=" + (ctx instanceof String s ? s.length() : 0);
            }
            case "stylist" -> "stylistFailed=" + state.getOrDefault(FashionState.STYLIST_FAILED, false);
            case "critic" -> "verdict=" + state.getOrDefault(FashionState.CRITIC_VERDICT, "-");
            default -> "";
        };
    }

    private static String stringValue(Map<String, Object> state, String key) {
        Object value = state == null ? null : state.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String nodeKey(Map<String, Object> state, String nodeName) {
        // executionId 无法从 state 稳定取得时退化为 runId；同 run 内节点串行，足够区分。
        String runId = stringValue(state, FashionState.RUN_ID);
        return (runId == null ? "?" : runId) + "\u0000" + nodeName;
    }

    private static String key(String executionId, String runId) {
        return executionId + "\u0000" + runId;
    }
}
