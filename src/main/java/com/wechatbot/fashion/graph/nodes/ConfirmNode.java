package com.wechatbot.fashion.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CriticOutput;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.fashion.look.model.TrendOutput;
import com.wechatbot.fashion.graph.FashionGraphContext;
import com.wechatbot.fashion.graph.FashionState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 节点 {@code confirm}：HITL 人工确认。
 *
 * <p>当 {@code app.fashion.graph.hitl.enabled=true} 且 planner 判定请求触发付费/高费用操作时，
 * 图会在此节点前由框架中断（{@code CompileConfig.interruptBefore("confirm")}），checkpoint 落 Redis，
 * 由上层向用户发出确认请求；用户回复后经 {@code FashionGraphRunner.resumeForResult} 恢复，
 * 携带 {@link FashionState#CONFIRM_APPROVED}。
 *
 * <ul>
 *   <li>确认 → 写 {@code confirmResult=approved}，路由继续到 rag；</li>
 *   <li>取消 → 预填 {@link FashionState#RESULT}（取消兜底），路由直接到 responder 结束。</li>
 * </ul>
 */
public class ConfirmNode implements AsyncNodeAction {

    private final FashionGraphContext ctx;

    public ConfirmNode(FashionGraphContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        boolean approved = state.value(FashionState.CONFIRM_APPROVED, Boolean.class).orElse(true);
        Map<String, Object> out = new HashMap<>();
        out.put(FashionState.CONFIRM_RESULT, approved ? "approved" : "rejected");

        if (!approved) {
            AnalyzedQuery analyzed = FashionState.readJson(
                    state, FashionState.PLAN, AnalyzedQuery.class, ctx.objectMapper()).orElse(null);
            String ragContext = state.value(FashionState.RAG_CONTEXT, String.class).orElse("");
            FashionResult cancelled = new FashionResult(
                    false, null, StylistOutput.empty(), CriticOutput.empty(), TrendOutput.neutral(),
                    ragContext, analyzed, "用户取消了本次操作", true);
            out.put(FashionState.RESULT, FashionState.writeJson(cancelled, ctx.objectMapper()));
        }
        return CompletableFuture.completedFuture(out);
    }
}
