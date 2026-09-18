package com.wechatbot.fashion.ai.orchestration;

/**
 * 工具调用作用域（ThreadLocal）：由对话网关在每次请求开始时设置一个 runId，
 * 使**图外**的工具调用（主模型 Function Calling）也能纳入工具预算与审计。
 *
 * <p>与 {@link com.wechatbot.fashion.graph.trajectory.TrajectoryRunContext} 分工：
 * 图内节点会设置/清理 TrajectoryRunContext，网关不应依赖它；本作用域独立存在，
 * {@code GovernedToolCallback} 优先用图 runId，缺失时回退到本作用域的网关 runId。
 */
public final class ToolCallScope {

    private static final ThreadLocal<String> RUN_ID = new ThreadLocal<>();

    private ToolCallScope() {
    }

    public static void begin(String runId) {
        if (runId == null || runId.isBlank()) {
            RUN_ID.remove();
            return;
        }
        RUN_ID.set(runId);
    }

    public static String current() {
        return RUN_ID.get();
    }

    public static void clear() {
        RUN_ID.remove();
    }
}
