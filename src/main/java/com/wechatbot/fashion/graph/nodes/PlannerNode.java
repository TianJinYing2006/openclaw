package com.wechatbot.fashion.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wechatbot.fashion.ai.fashion.look.ConfirmationIntent;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.graph.FashionGraphContext;
import com.wechatbot.fashion.graph.FashionResultBuilders;
import com.wechatbot.fashion.graph.FashionState;
import com.wechatbot.fashion.graph.plan.ExecutionPlan;
import com.wechatbot.fashion.graph.plan.PlanBuilder;
import com.wechatbot.fashion.graph.trajectory.TrajectoryRunContext;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 节点 {@code planner}：调用 QueryAnalyzer 做需求分析（异常则关键词兜底），落对话初稿，
 * 并判定是否简单请求（决定后续是否跳过 Critic/Trend/Coordinator）。
 *
 * <p>对应原 {@code AgentCoordinator} Step 1（分析）+ 保存对话初稿。
 * 富类型 {@link AnalyzedQuery} 以 JSON 字符串存放（checkpoint 不保型，见 {@link FashionState#writeJson}）。
 */
public class PlannerNode implements AsyncNodeAction {

    private final FashionGraphContext ctx;

    public PlannerNode(FashionGraphContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String userId = state.value(FashionState.USER_ID, String.class).orElse("");
        String query = state.value(FashionState.QUERY, String.class).orElse("");
        String memory = state.value(FashionState.MEMORY, String.class).orElse("");

        AnalyzedQuery analyzed;
        TrajectoryRunContext.set(state.value(FashionState.RUN_ID, String.class).orElse(null), "planner");
        try {
            analyzed = ctx.queryAnalyzer().analyze(query, memory);
        } catch (Exception e) {
            analyzed = AnalyzedQuery.fallback(query);
        } finally {
            TrajectoryRunContext.clear();
        }

        boolean simple = FashionResultBuilders.isSimpleRequest(analyzed);

        Long conversationId = null;
        if (analyzed.params() != null) {
            conversationId = ctx.conversationService().saveInitial(
                    userId,
                    query,
                    analyzed.params().scene(),
                    analyzed.params().season(),
                    analyzed.params().formality());
        }

        Map<String, Object> out = new HashMap<>();
        out.put(FashionState.PLAN, FashionState.writeJson(analyzed, ctx.objectMapper()));
        // 3.3：把分析升级为结构化执行计划，供 ToolLoopNode 等节点消费（真正驱动执行）
        ExecutionPlan plan = PlanBuilder.from(analyzed, query);
        out.put(FashionState.EXECUTION_PLAN, FashionState.writeJson(plan, ctx.objectMapper()));
        out.put(FashionState.SIMPLE, simple);
        out.put(FashionState.CONVERSATION_ID, conversationId);
        // HITL：请求命中付费操作意图时标记，供 confirm 节点中断等待用户确认
        out.put(FashionState.CONFIRM_REQUIRED, ConfirmationIntent.requiresConfirmation(query));
        return CompletableFuture.completedFuture(out);
    }
}
