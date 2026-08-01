package com.example.ykdsummer.ai.fashion.profile;

import com.example.ykdsummer.ai.fashion.model.FashionConversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户画像服务（Embedding 增强版）。
 *
 * <p>从 {@code fashion_conversations} 表中提取用户历史穿搭记录，
 * 拼接成偏好上下文文本，注入到 Agent Prompt 中。
 *
 * <p>检索策略（分层降级）：
 * <ol>
 *   <li><b>向量相似度检索</b>：对当前用户输入生成 Embedding，
 *       与该用户所有历史对话的 Embedding 做余弦相似度排序，取 Top-K 最相关的记录。
 *       能从全量历史中找到语义最相近的偏好，而非仅看最近几条。</li>
 *   <li><b>最近 N 条兜底</b>：当 Embedding 生成失败或历史记录无向量时，
 *       回退到取最近 5 条记录。</li>
 * </ol>
 *
 * <p>冷启动（无历史记录）时返回空字符串，不干扰原流程。
 */
@Component
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    /** 向量检索时从历史中取的候选池大小（从这些里再按相似度选 Top-K） */
    private static final int EMBEDDING_CANDIDATE_POOL = 20;
    /** 最终注入 Prompt 的记录条数 */
    private static final int TOP_K = 5;
    /** 兜底策略取最近条数 */
    private static final int FALLBACK_LIMIT = 5;

    private final FashionConversationService conversationService;
    private final FashionEmbeddingService embeddingService;

    public UserProfileService(FashionConversationService conversationService,
                              FashionEmbeddingService embeddingService) {
        this.conversationService = conversationService;
        this.embeddingService = embeddingService;
    }

    /**
     * 构建用户偏好上下文（无相似度检索，取最近 N 条）。
     *
     * <p>用于不需要当前输入做参照的场景。
     */
    public String buildProfileContext(String userId) {
        return buildProfileContext(userId, null);
    }

    /**
     * 构建用户偏好上下文（向量相似度检索增强）。
     *
     * <p>对当前用户输入生成 Embedding，与历史记录做相似度排序，取 Top-K。
     * Embedding 不可用时降级为最近 N 条。
     *
     * @param userId       用户 ID
     * @param currentInput 当前用户输入（用于相似度检索的参照文本）
     * @return 偏好上下文文本；无数据时返回空字符串
     */
    public String buildProfileContext(String userId, String currentInput) {
        if (userId == null || userId.isBlank()) {
            return "";
        }

        // 尝试向量相似度检索
        List<FashionConversation> selected = selectBySimilarity(userId, currentInput);

        // 降级：相似度检索无结果时取最近 N 条
        if (selected.isEmpty()) {
            selected = conversationService.findRecent(userId, FALLBACK_LIMIT);
            if (!selected.isEmpty()) {
                log.debug("Fallback to recent {} records for user {}", selected.size(), userId);
            }
        }

        if (selected.isEmpty()) {
            log.debug("No fashion history for user {}, cold start", userId);
            return "";
        }

        return formatProfileContext(selected);
    }

    /**
     * 向量相似度检索：对当前输入生成 Embedding，与历史记录排序取 Top-K。
     */
    private List<FashionConversation> selectBySimilarity(String userId, String currentInput) {
        if (currentInput == null || currentInput.isBlank()) {
            return List.of();
        }

        // 生成当前输入的 Embedding
        float[] queryEmbedding = embeddingService.embed(currentInput);
        if (queryEmbedding == null) {
            log.debug("Embedding generation failed for input, skipping similarity search");
            return List.of();
        }

        // 取候选池（最近 20 条，包含 embedding 字段）
        List<FashionConversation> candidates = conversationService.findRecent(userId, EMBEDDING_CANDIDATE_POOL);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 反序列化候选向量，收集带 embedding 的记录
        List<FashionConversation> withEmbedding = new ArrayList<>();
        List<float[]> embeddings = new ArrayList<>();
        for (FashionConversation conv : candidates) {
            float[] emb = embeddingService.deserialize(conv.embedding());
            if (emb != null) {
                withEmbedding.add(conv);
                embeddings.add(emb);
            }
        }

        if (withEmbedding.isEmpty()) {
            log.debug("No conversations with embeddings for user {}", userId);
            return List.of();
        }

        // 向量相似度检索 Top-K
        List<FashionEmbeddingService.ScoredIndex> topK =
                FashionEmbeddingService.findTopKSimilar(queryEmbedding, embeddings, TOP_K);
        List<FashionConversation> result = new ArrayList<>();
        for (FashionEmbeddingService.ScoredIndex si : topK) {
            result.add(withEmbedding.get(si.index()));
        }

        log.info("Similarity search: {} candidates, {} with embeddings, selected top {} for user {}",
                candidates.size(), withEmbedding.size(), result.size(), userId);
        return result;
    }

    private String formatProfileContext(List<FashionConversation> conversations) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- 用户历史穿搭偏好 ---\n");
        for (FashionConversation conv : conversations) {
            sb.append("[").append(conv.scene()).append("]");
            sb.append(" 需求：").append(truncate(conv.userInput(), 40));
            if (conv.recommendation() != null && !conv.recommendation().isEmpty()) {
                sb.append(" | 推荐：").append(truncate(conv.recommendation(), 50));
            }
            if (conv.userFeedback() != null && !conv.userFeedback().isEmpty()) {
                sb.append(" | 反馈：").append(conv.userFeedback());
            }
            sb.append("\n");
        }
        sb.append("--- 请结合以上偏好，优先考虑用户的习惯倾向 ---\n");

        log.debug("Built profile context with {} records ({} chars)",
                conversations.size(), sb.length());
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.isBlank()) return "";
        String trimmed = s.strip().replaceAll("\\s+", " ");
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen) + "…";
    }
}
