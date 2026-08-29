package com.example.ykdsummer.ai.fashion.look.rag;

import com.example.ykdsummer.ai.fashion.look.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.look.model.RetrievedChunk;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 穿搭知识检索服务接口（RAG 层）。
 *
 * <p>定义统一的检索和格式化契约，具体实现可切换：
 * <ul>
 *   <li>{@link MysqlFtsKnowledgeService} — MySQL FULLTEXT 检索（降级方案）</li>
 *   <li>{@link RagFlowKnowledgeService} — RAGFlow 向量+关键词检索（主方案）</li>
 * </ul>
 *
 * <p>检索在 Agent 管道启动前统一执行一次，结果共享给所有 Agent。
 */
public interface FashionKnowledgeService {

    /**
     * 检索穿搭知识（不排除历史推荐）。
     *
     * @param query 查询分析结果
     * @return 检索到的穿搭案例列表，失败返回空列表
     */
    List<RetrievedChunk> retrieve(AnalyzedQuery query);

    /**
     * 检索穿搭知识，并排除指定 outfit 编号（历史滑动窗口去重）。
     *
     * <p>排除集内的编号（如最近已推荐过的参考穿搭）不进入候选池，降低跨次
     * 推荐重复率；排除后候选不足时返回过滤后的全部（不强凑不相关结果）。
     *
     * @param query      查询分析结果
     * @param excludeIds 需要排除的 outfit 编号集合，可为空
     * @return 检索到的穿搭案例列表，失败返回空列表
     */
    default List<RetrievedChunk> retrieveExcluding(AnalyzedQuery query, Collection<String> excludeIds) {
        if (excludeIds == null || excludeIds.isEmpty()) {
            return retrieve(query);
        }
        return retrieve(query).stream()
                .filter(c -> c.entry().id() == null || !excludeIds.contains(c.entry().id()))
                .toList();
    }

    /**
     * 将检索结果格式化为 Agent prompt 可用的文本。
     *
     * @param chunks 检索结果列表
     * @return 格式化的知识参考文本
     */
    String formatContext(List<RetrievedChunk> chunks);
}
