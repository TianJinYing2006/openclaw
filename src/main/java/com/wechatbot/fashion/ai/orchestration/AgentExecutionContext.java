package com.wechatbot.fashion.ai.orchestration;

import com.wechatbot.fashion.graph.trajectory.TrajectoryRunContext;

import java.util.function.Supplier;

/**
 * 显式 Agent 执行上下文：把 {@link AgentSessionContext} 与 {@link TrajectoryRunContext} 的
 * 当前值打包成不可变快照，供跨线程（parallelExecutor / agentLlmCallerExecutor）显式传播。
 *
 * <p>动机（3.4）：这两个上下文都是 ThreadLocal，线程池里的并行节点/工具调用读不到主线程的值
 * （表现为 userId=anonymous、轨迹缺 runId）。调用方在**提交异步任务前** {@link #capture()}，
 * 在异步线程内用 {@link #callWith(Snapshot, Supplier)} 应用并自动恢复，避免污染复用线程。
 */
public final class AgentExecutionContext {

    /** 一次执行上下文的不可变快照。 */
    public record Snapshot(String userId, String sessionId, String contextToken, String runId, String node) {
    }

    private AgentExecutionContext() {
    }

    /** 捕获当前线程的上下文（在提交异步任务前调用）。 */
    public static Snapshot capture() {
        TrajectoryRunContext.Ref ref = TrajectoryRunContext.current();
        return new Snapshot(
                AgentSessionContext.currentUserId(),
                AgentSessionContext.currentSessionId(),
                AgentSessionContext.currentContextToken(),
                ref.present() ? ref.runId() : null,
                ref.node());
    }

    /**
     * 在给定快照的上下文中执行 action，执行后恢复到调用前的上下文（避免污染线程池线程）。
     */
    public static <T> T callWith(Snapshot snapshot, Supplier<T> action) {
        Snapshot previous = capture();
        apply(snapshot);
        try {
            return action.get();
        } finally {
            apply(previous);
        }
    }

    private static void apply(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.userId() != null) {
            AgentSessionContext.set(snapshot.userId(), snapshot.sessionId(), snapshot.contextToken());
        } else {
            AgentSessionContext.clear();
        }
        TrajectoryRunContext.set(snapshot.runId(), snapshot.node());
    }
}
