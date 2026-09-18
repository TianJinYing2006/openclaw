package com.wechatbot.fashion.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CoordinatorOutput;
import com.wechatbot.fashion.ai.fashion.look.model.CriticOutput;
import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.fashion.look.model.TrendOutput;
import com.wechatbot.fashion.graph.FashionGraphContext;
import com.wechatbot.fashion.graph.FashionResultBuilders;
import com.wechatbot.fashion.graph.FashionState;
import com.wechatbot.fashion.graph.trajectory.TrajectoryRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 节点 {@code responder}：综合裁决并组装最终 {@link FashionResult}。
 *
 * <p>对应原 {@code AgentCoordinator} 的「简单请求直接返回 Stylist 结果」「Step 5 Coordinator 裁决」
 * 与「Coordinator 失败降级」三态：
 * <ul>
 *   <li>若 {@link FashionState#STYLIST_FAILED}（stylist 已预填安全兜底 RESULT）→ 直接采用；</li>
 *   <li>若 {@link FashionState#SIMPLE} → 用 Stylist 首选方案构造 CoordinatorOutput；</li>
 *   <li>否则 → 调 CoordinatorAgent 裁决（异常则降级 Stylist 首选）。</li>
 * </ul>
 * 仅在图权威（非影子）时持久化对话摘要 + 嵌入，避免与老路径双写。
 * 富类型均以 JSON 字符串存放/读取（见 {@link FashionState#writeJson}/{@link FashionState#readJson}）。
 */
public class ResponderNode implements AsyncNodeAction {

    private static final Logger log = LoggerFactory.getLogger(ResponderNode.class);

    private final FashionGraphContext ctx;

    public ResponderNode(FashionGraphContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        // Stylist 已预填安全兜底：直接采用，仅负责最终持久化
        FashionResult prefilled = FashionState.readJson(state, FashionState.RESULT, FashionResult.class, ctx.objectMapper()).orElse(null);
        if (prefilled != null) {
            maybePersist(state, prefilled);
            return CompletableFuture.completedFuture(Map.of());
        }

        String userId = state.value(FashionState.USER_ID, String.class).orElse("");
        String query = state.value(FashionState.QUERY, String.class).orElse("");
        FashionRequest request = new FashionRequest(userId, query);
        StylistOutput stylist = FashionState.readJson(state, FashionState.STYLIST_OUT, StylistOutput.class, ctx.objectMapper()).orElse(null);
        CriticOutput critic = FashionState.readJson(state, FashionState.CRITIC_OUT, CriticOutput.class, ctx.objectMapper()).orElse(CriticOutput.empty());
        TrendOutput trend = FashionState.readJson(state, FashionState.TREND_OUT, TrendOutput.class, ctx.objectMapper()).orElse(TrendOutput.neutral());
        String ragContext = state.value(FashionState.RAG_CONTEXT, String.class).orElse("");
        AnalyzedQuery analyzed = FashionState.readJson(state, FashionState.PLAN, AnalyzedQuery.class, ctx.objectMapper()).orElse(null);
        boolean simple = state.value(FashionState.SIMPLE, Boolean.class).orElse(false);

        FashionResult result;
        if (simple) {
            result = new FashionResult(true, FashionResultBuilders.fromStylist(stylist), stylist,
                    CriticOutput.empty(), TrendOutput.neutral(), ragContext, analyzed, null, false);
        } else {
            CoordinatorOutput coordinator;
            String degradeReason;
            TrajectoryRunContext.set(state.value(FashionState.RUN_ID, String.class).orElse(null), "responder");
            try {
                coordinator = ctx.coordinatorAgent().execute(
                        request, stylist, critic, trend, FashionResultBuilders.compactRagContext(ragContext));
                // 无异常但返回 null：LLM 调用/解析在多轮重试后仍失败，与抛异常是两条不同路径
                degradeReason = coordinator == null
                        ? "Coordinator 无输出（调用或解析失败），降级为 Stylist 首选方案"
                        : null;
            } catch (Exception e) {
                log.warn("Responder Coordinator degraded to Stylist first suggestion", e);
                coordinator = null;
                degradeReason = isTimeout(e)
                        ? "Coordinator 调用超时，降级为 Stylist 首选方案"
                        : "Coordinator 调用异常（" + conciseCause(e) + "），降级为 Stylist 首选方案";
            } finally {
                TrajectoryRunContext.clear();
            }
            if (coordinator == null) {
                result = FashionResultBuilders.degraded(stylist, critic, trend, ragContext, analyzed,
                        degradeReason);
            } else {
                result = new FashionResult(true, coordinator, stylist, critic, trend, ragContext, analyzed, null, false);
            }
        }

        maybePersist(state, result);
        return CompletableFuture.completedFuture(Map.of(FashionState.RESULT, FashionState.writeJson(result, ctx.objectMapper())));
    }

    /** 仅当图权威（非影子）时持久化，避免与旧影子路径双写。 */
    private void maybePersist(OverAllState state, FashionResult result) {
        if (!ctx.persist()) {
            return;
        }
        Long conversationId = state.value(FashionState.CONVERSATION_ID, Long.class).orElse(null);
        if (conversationId == null) {
            return;
        }
        String userId = state.value(FashionState.USER_ID, String.class).orElse("");
        String userInput = state.value(FashionState.QUERY, String.class).orElse("");
        try {
            String summary = ctx.formatter().summarize(result);
            ctx.conversationService().updateRecommendation(conversationId, summary);
            ctx.conversationService().updateReferenceOutfit(conversationId,
                    FashionResultBuilders.resolveEffectiveOutfitId(result, ctx.referenceImageResolver()));
            saveEmbeddingAsync(conversationId, userInput, summary);
        } catch (Exception e) {
            log.warn("Responder persistence failed for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    /** 网络/超时类异常（与 {@code AgentLlmCaller} 的判定口径一致），用于区分降级文案。 */
    private static boolean isTimeout(Throwable t) {
        if (t instanceof java.util.concurrent.TimeoutException) return true;
        if (t instanceof java.net.SocketTimeoutException) return true;
        String msg = t.getMessage();
        return msg != null && (msg.toLowerCase().contains("timeout") || msg.toLowerCase().contains("timed out"));
    }

    /** 提取可用于用户侧展示的简短原因（截断避免长堆栈/敏感参数进入结果文案）。 */
    private static String conciseCause(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        String single = msg.replace('\n', ' ').trim();
        return single.length() > 60 ? single.substring(0, 60) + "…" : single;
    }

    /** 异步生成对话 Embedding 并回填（不阻塞管道返回）。 */
    private void saveEmbeddingAsync(Long conversationId, String userInput, String recommendation) {
        if (conversationId == null) {
            return;
        }
        String embeddingText = userInput + " " + (recommendation != null ? recommendation : "");
        CompletableFuture.runAsync(() -> {
            try {
                float[] embedding = ctx.embeddingService().embed(embeddingText);
                if (embedding != null) {
                    ctx.conversationService().updateEmbedding(conversationId, ctx.embeddingService().serialize(embedding));
                }
            } catch (Exception e) {
                log.warn("Async embedding save failed for conversation {}: {}", conversationId, e.getMessage());
            }
        }, ctx.parallelExecutor());
    }
}
