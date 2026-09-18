package com.wechatbot.fashion.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.graph.FashionGraphContext;
import com.wechatbot.fashion.graph.FashionResultBuilders;
import com.wechatbot.fashion.graph.FashionState;
import com.wechatbot.fashion.graph.trajectory.TrajectoryRunContext;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 节点 {@code stylist}：调用 StylistAgent 生成搭配方案。
 *
 * <p>对应原 {@code AgentCoordinator} Step 3。Stylist 失败（null/empty）时预填安全兜底
 * {@link FashionState#RESULT}（JSON 字符串），并置 {@link FashionState#STYLIST_FAILED}=true，使后续直接走向
 * responder 结束，与原管道「Stylist 失败 → 安全兜底」一致。
 */
public class StylistNode implements AsyncNodeAction {

    private final FashionGraphContext ctx;

    public StylistNode(FashionGraphContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String userId = state.value(FashionState.USER_ID, String.class).orElse("");
        String query = state.value(FashionState.QUERY, String.class).orElse("");
        FashionRequest request = new FashionRequest(userId, query);
        String ragContext = state.value(FashionState.RAG_CONTEXT, String.class).orElse("");
        // P0-2：tool_loop 产出的外部工具上下文（如实时天气）追加到 RAG 上下文，供 Stylist 参考
        String toolContext = state.value(FashionState.TOOL_CONTEXT, String.class).orElse("");
        if (!toolContext.isBlank()) {
            ragContext = ragContext + "\n\n【实时外部信息】" + toolContext;
        }
        AnalyzedQuery analyzed = FashionState.readJson(state, FashionState.PLAN, AnalyzedQuery.class, ctx.objectMapper()).orElse(null);
        String memory = state.value(FashionState.MEMORY, String.class).orElse("");

        StylistOutput stylist;
        TrajectoryRunContext.set(state.value(FashionState.RUN_ID, String.class).orElse(null), "stylist");
        try {
            stylist = ctx.stylistAgent().execute(request, ragContext, analyzed, memory);
        } catch (Exception e) {
            stylist = null;
        } finally {
            TrajectoryRunContext.clear();
        }

        Map<String, Object> out = new HashMap<>();
        if (stylist == null || stylist.isEmpty()) {
            String scene = analyzed != null && analyzed.params() != null ? analyzed.params().scene() : "DAILY";
            out.put(FashionState.STYLIST_FAILED, true);
            out.put(FashionState.RESULT, FashionState.writeJson(
                    FashionResultBuilders.safetyFallback(scene, analyzed), ctx.objectMapper()));
            return CompletableFuture.completedFuture(out);
        }
        out.put(FashionState.STYLIST_OUT, FashionState.writeJson(stylist, ctx.objectMapper()));
        out.put(FashionState.STYLIST_FAILED, false);
        return CompletableFuture.completedFuture(out);
    }
}
