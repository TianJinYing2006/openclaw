package com.wechatbot.fashion.graph.trajectory;

/**
 * 当前 Agent 图节点执行上下文（ThreadLocal）：携带 runId 与节点名，
 * 供 {@code AgentLlmCaller} 在发起模型调用时把 MODEL step 归属到正确的 run / 节点。
 *
 * <p>注意：本项目大量使用 {@code parallelExecutor} / {@code agentLlmCallerExecutor}，
 * ThreadLocal 不会跨线程传播。因此每个会触发 LLM 的节点（含 CriticNode 的并行 lambda）
 * 必须在自己的执行线程上显式 {@link #set(String, String)}，并在 finally 中 {@link #clear()}。
 * 调用方必须在进入异步线程前读取 {@link #current()}，因为跨线程后上下文已丢失。
 */
public final class TrajectoryRunContext {

    /** runId + 节点名；node 为空表示未归属到具体节点。 */
    public record Ref(String runId, String node) {
        public static Ref unknown() {
            return new Ref(null, null);
        }

        public boolean present() {
            return runId != null && !runId.isBlank();
        }
    }

    private static final ThreadLocal<Ref> HOLDER = new ThreadLocal<>();

    private TrajectoryRunContext() {
    }

    public static void set(String runId, String node) {
        if (runId == null || runId.isBlank()) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(new Ref(runId, node));
    }

    public static Ref current() {
        Ref ref = HOLDER.get();
        return ref == null ? Ref.unknown() : ref;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
