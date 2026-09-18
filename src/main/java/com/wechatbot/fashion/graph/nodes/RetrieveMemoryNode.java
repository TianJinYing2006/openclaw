package com.wechatbot.fashion.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wechatbot.fashion.graph.FashionGraphContext;
import com.wechatbot.fashion.graph.FashionState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 节点 {@code retrieve_memory}：构建用户画像上下文，并查询最近已推荐过的 outfit 编号用于 RAG 去重。
 *
 * <p>对应原 {@code AgentCoordinator} Step 0（画像）+ Step 2 的 exclude 集合准备。
 */
public class RetrieveMemoryNode implements AsyncNodeAction {

    /** 历史滑动窗口：检索时排除最近 N 次推荐已命中过的参考穿搭，降低跨次重复率。 */
    private static final int RECENT_RECOMMENDATION_WINDOW = 5;

    private final FashionGraphContext ctx;

    public RetrieveMemoryNode(FashionGraphContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String userId = state.value(FashionState.USER_ID, String.class).orElse("");
        String query = state.value(FashionState.QUERY, String.class).orElse("");

        String profileContext = ctx.userProfileService().buildProfileContext(userId, query);
        List<String> excludeIds = new ArrayList<>(
                ctx.conversationService().findRecentReferenceOutfits(userId, RECENT_RECOMMENDATION_WINDOW));

        Map<String, Object> out = new HashMap<>();
        out.put(FashionState.MEMORY, profileContext == null ? "" : profileContext);
        out.put(FashionState.EXCLUDE_IDS, excludeIds);
        return CompletableFuture.completedFuture(out);
    }
}
