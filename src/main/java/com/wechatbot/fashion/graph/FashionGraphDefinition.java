package com.wechatbot.fashion.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.wechatbot.fashion.graph.nodes.ConfirmNode;
import com.wechatbot.fashion.graph.nodes.CriticNode;
import com.wechatbot.fashion.graph.nodes.PlannerNode;
import com.wechatbot.fashion.graph.nodes.RagNode;
import com.wechatbot.fashion.graph.nodes.RetrieveMemoryNode;
import com.wechatbot.fashion.graph.nodes.ResponderNode;
import com.wechatbot.fashion.graph.nodes.StylistNode;
import com.wechatbot.fashion.graph.nodes.ToolLoopNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fashion 子图的 {@link StateGraph} 定义（单脊梁：spring-ai-alibaba-graph）。
 *
 * <p>拓扑（与原 {@code AgentCoordinator} 5 步管道语义对齐，含 HITL）：
 * <pre>
 *   START → retrieve_memory → planner →[HITL confirm]→ rag → tool_loop → stylist
 *                          (hitl enabled && 付费意图 ? confirm : rag)      │
 *                          confirm(approved?rag:responder)   (STYLIST_FAILED || SIMPLE ? responder : critic)
 *                                                                          │
 *                                                                        critic
 *                                                                          │
 *                            (loopEnabled && reject && iteration<MAX ? stylist : responder)
 *                                                                          │
 *                                                                      responder → END
 * </pre>
 *
 * <p>「简单请求」判定依赖 {@code AnalyzedQuery}（planner 产出），故分支点放在 stylist 之后，而非 START；
 * 原管道对简单请求仍跑完 Step0–3 再跳过 Critic/Trend/Coordinator，本图一致。
 *
 * <p>阶段 3：节点已迁入真实 LLM / RAG / Memory 逻辑；本图默认不切流量，由 {@code FashionAgentService}
 * 按开关以影子/权威方式调用。
 */
public final class FashionGraphDefinition {

    public static final String N_RETRIEVE = "retrieve_memory";
    public static final String N_PLANNER = "planner";
    public static final String N_CONFIRM = "confirm";
    public static final String N_RAG = "rag";
    public static final String N_TOOL_LOOP = "tool_loop";
    public static final String N_STYLIST = "stylist";
    public static final String N_CRITIC = "critic";
    public static final String N_RESPONDER = "responder";

    private FashionGraphDefinition() {
    }

    public static StateGraph build(FashionGraphContext ctx) throws Exception {
        StateGraph g = new StateGraph();

        g.addNode(N_RETRIEVE, new RetrieveMemoryNode(ctx));
        g.addNode(N_PLANNER, new PlannerNode(ctx));
        // HITL：命中付费操作意图时经 confirm 中断等待用户确认（hitl 关闭时该节点不进入）
        g.addNode(N_CONFIRM, new ConfirmNode(ctx));
        g.addNode(N_RAG, new RagNode(ctx));
        // P0-2：自主工具循环（开关关闭时 ToolLoopNode 零开销透传，等价于旧拓扑）
        g.addNode(N_TOOL_LOOP, new ToolLoopNode(ctx));
        g.addNode(N_STYLIST, new StylistNode(ctx));
        g.addNode(N_CRITIC, new CriticNode(ctx));
        g.addNode(N_RESPONDER, new ResponderNode(ctx));

        // 顺序链：START → retrieve_memory → planner →(HITL confirm)→ rag → tool_loop → stylist
        g.addEdge(StateGraph.START, N_RETRIEVE);
        g.addEdge(N_RETRIEVE, N_PLANNER);
        g.addEdge(N_RAG, N_TOOL_LOOP);
        g.addEdge(N_TOOL_LOOP, N_STYLIST);

        // planner 路由：HITL 开启且命中付费意图 → confirm，否则直接 rag
        AsyncEdgeAction plannerRoute = (OverAllState s) -> {
            boolean needConfirm = ctx.hitlEnabled()
                    && s.value(FashionState.CONFIRM_REQUIRED, Boolean.class).orElse(false);
            return CompletableFuture.completedFuture(needConfirm ? N_CONFIRM : N_RAG);
        };
        g.addConditionalEdges(N_PLANNER, plannerRoute, Map.of(N_CONFIRM, N_CONFIRM, N_RAG, N_RAG));

        // confirm 路由：确认 → rag；取消 → responder（此时 RESULT 已被 ConfirmNode 预填）
        AsyncEdgeAction confirmRoute = (OverAllState s) -> {
            boolean rejected = "rejected".equals(s.value(FashionState.CONFIRM_RESULT, String.class).orElse(""));
            return CompletableFuture.completedFuture(rejected ? N_RESPONDER : N_RAG);
        };
        g.addConditionalEdges(N_CONFIRM, confirmRoute, Map.of(N_RAG, N_RAG, N_RESPONDER, N_RESPONDER));

        // stylist 路由：失败或简单请求 → responder；否则 → critic
        AsyncEdgeAction stylistRoute = (OverAllState s) -> {
            boolean failed = s.value(FashionState.STYLIST_FAILED, Boolean.class).orElse(false);
            boolean simple = s.value(FashionState.SIMPLE, Boolean.class).orElse(false);
            String target = (failed || simple) ? N_RESPONDER : N_CRITIC;
            return CompletableFuture.completedFuture(target);
        };
        g.addConditionalEdges(N_STYLIST, stylistRoute,
                Map.of(N_RESPONDER, N_RESPONDER, N_CRITIC, N_CRITIC));

        // critic 路由：驳回且未超上限且循环开启 → 回边 stylist；否则 → responder
        AsyncEdgeAction criticRoute = (OverAllState s) -> {
            String verdict = s.value(FashionState.CRITIC_VERDICT, String.class).orElse("approve");
            int it = s.value(FashionState.ITERATION, Integer.class).orElse(0);
            boolean loop = ctx.loopEnabled()
                    && "reject".equals(verdict)
                    && it < FashionState.MAX_CRITIC_LOOP;
            String target = loop ? N_STYLIST : N_RESPONDER;
            return CompletableFuture.completedFuture(target);
        };
        g.addConditionalEdges(N_CRITIC, criticRoute,
                Map.of(N_STYLIST, N_STYLIST, N_RESPONDER, N_RESPONDER));

        // responder → END
        g.addEdge(N_RESPONDER, StateGraph.END);

        return g;
    }
}
