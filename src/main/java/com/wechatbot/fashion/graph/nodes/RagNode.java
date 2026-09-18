package com.wechatbot.fashion.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.RetrievedChunk;
import com.wechatbot.fashion.graph.FashionGraphContext;
import com.wechatbot.fashion.graph.FashionState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 节点 {@code rag}：RAG 知识检索（排除最近已推荐 outfit），并格式化为上下文文本。
 *
 * <p>对应原 {@code AgentCoordinator} Step 2。检索异常则降级为空上下文，不阻断管道。
 * 富类型 {@link AnalyzedQuery} 从 JSON 字符串读回（见 {@link FashionState#readJson}）。
 */
public class RagNode implements AsyncNodeAction {

    private final FashionGraphContext ctx;

    public RagNode(FashionGraphContext ctx) {
        this.ctx = ctx;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        AnalyzedQuery query = FashionState.readJson(state, FashionState.PLAN, AnalyzedQuery.class, ctx.objectMapper()).orElse(null);
        List<String> excludeIds = state.value(FashionState.EXCLUDE_IDS, List.class).orElse(List.of());

        List<RetrievedChunk> chunks;
        try {
            chunks = ctx.knowledgeService().retrieveExcluding(query, new HashSet<>(excludeIds));
        } catch (Exception e) {
            chunks = List.of();
        }
        String ragContext = ctx.knowledgeService().formatContext(chunks);

        Map<String, Object> out = new HashMap<>();
        out.put(FashionState.RAG_CONTEXT, ragContext == null ? "" : ragContext);
        return CompletableFuture.completedFuture(out);
    }
}
