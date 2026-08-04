package com.example.ykdsummer.ai.fashion.rag;

import com.example.ykdsummer.ai.fashion.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.model.RetrievedChunk;

import java.util.List;

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
     * 检索穿搭知识。
     *
     * @param query 查询分析结果
     * @return 检索到的穿搭案例列表，失败返回空列表
     */
    List<RetrievedChunk> retrieve(AnalyzedQuery query);

    /**
     * 将检索结果格式化为 Agent prompt 可用的文本。
     *
     * @param chunks 检索结果列表
     * @return 格式化的知识参考文本
     */
    String formatContext(List<RetrievedChunk> chunks);
}
