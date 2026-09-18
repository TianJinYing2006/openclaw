package com.wechatbot.fashion.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CriticOutput;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.fashion.look.model.TrendOutput;
import com.wechatbot.fashion.ai.orchestration.AgentExecutionContext;
import com.wechatbot.fashion.graph.FashionGraphContext;
import com.wechatbot.fashion.graph.FashionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 节点 {@code critic}：Critic ∥ Trend 并行评审（复用 parallelExecutor），推导驳回裁决，并递增循环计数。
 *
 * <p>CriticOutput 无显式 approve/reject 字段，驳回由综合评分（overallScore）推导：
 * 最高分 &lt; {@link FashionGraphContext#criticPassThreshold()} 视为 reject。
 *
 * <p>对应原 {@code AgentCoordinator} Step 4（Critic ∥ Trend 并行）。循环是否回边到 stylist 由
 * {@link FashionGraphDefinition} 的 critic 路由 + {@link FashionGraphContext#loopEnabled()} 控制。
 * 富类型 StylistOutput / CriticOutput / TrendOutput 均以 JSON 字符串存放。
 */
public class CriticNode implements AsyncNodeAction {

    private static final Logger log = LoggerFactory.getLogger(CriticNode.class);

    private final FashionGraphContext ctx;

    public CriticNode(FashionGraphContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        StylistOutput stylist = FashionState.readJson(state, FashionState.STYLIST_OUT, StylistOutput.class, ctx.objectMapper()).orElse(null);
        AnalyzedQuery analyzed = FashionState.readJson(state, FashionState.PLAN, AnalyzedQuery.class, ctx.objectMapper()).orElse(null);
        int it = state.value(FashionState.ITERATION, Integer.class).orElse(0);

        String runId = state.value(FashionState.RUN_ID, String.class).orElse(null);
        // 3.4：ThreadLocal 不跨线程；在提交前捕获显式上下文，异步线程内应用并自动恢复
        AgentExecutionContext.Snapshot execCtx = AgentExecutionContext.capture();
        CompletableFuture<CriticOutput> criticF = CompletableFuture.supplyAsync(
                () -> AgentExecutionContext.callWith(withNode(execCtx, runId, "critic"), () -> {
                    try {
                        return ctx.criticAgent().execute(stylist, analyzed);
                    } catch (Exception e) {
                        return CriticOutput.empty();
                    }
                }), ctx.parallelExecutor());

        CompletableFuture<TrendOutput> trendF = CompletableFuture.supplyAsync(
                () -> AgentExecutionContext.callWith(withNode(execCtx, runId, "trend"), () -> {
                    try {
                        return ctx.trendAgent().execute(stylist, analyzed);
                    } catch (Exception e) {
                        return TrendOutput.neutral();
                    }
                }), ctx.parallelExecutor());

        // 节点级 deadline：Critic∥Trend 共享一个截止时间，超时按中性结果降级并 cancel 未完成任务，
        // 避免个别评审任务阻塞整个图（Runner 端还有框架递归上限兜底）。
        long deadlineNanos = System.nanoTime() + ctx.criticDeadlineMillis() * 1_000_000L;
        CriticOutput critic = await(criticF, deadlineNanos, CriticOutput.empty());
        TrendOutput trend = await(trendF, deadlineNanos, TrendOutput.neutral());

        int best = 0;
        if (critic.reviews() != null) {
            for (CriticOutput.Critique c : critic.reviews()) {
                if (c.overallScore() > best) {
                    best = c.overallScore();
                }
            }
        }
        boolean reject = best < ctx.criticPassThreshold();

        Map<String, Object> out = new HashMap<>();
        out.put(FashionState.CRITIC_OUT, FashionState.writeJson(critic, ctx.objectMapper()));
        out.put(FashionState.TREND_OUT, FashionState.writeJson(trend, ctx.objectMapper()));
        out.put(FashionState.CRITIC_VERDICT, reject ? "reject" : "approve");
        out.put(FashionState.ITERATION, it + 1);
        return CompletableFuture.completedFuture(out);
    }

    /** 基于捕获的上下文派生「指定节点」的快照。 */
    private static AgentExecutionContext.Snapshot withNode(AgentExecutionContext.Snapshot base,
                                                           String runId, String node) {
        return new AgentExecutionContext.Snapshot(
                base.userId(), base.sessionId(), base.contextToken(), runId, node);
    }

    /** 在节点 deadline 内等待并行任务；超时/异常返回中性兜底并取消未完成任务。 */
    private static <V> V await(CompletableFuture<V> future, long deadlineNanos, V fallback) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            future.cancel(true);
            log.warn("critic node deadline reached before wait; degrade to neutral");
            return fallback;
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("critic node deadline exceeded; cancelled remaining review task and degrade to neutral");
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return fallback;
        } catch (Exception e) {
            future.cancel(true);
            return fallback;
        }
    }
}
