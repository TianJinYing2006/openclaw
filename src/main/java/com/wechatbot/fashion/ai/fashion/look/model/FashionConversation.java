package com.wechatbot.fashion.ai.fashion.look.model;

/**
 * 穿搭对话记录（用户画像数据源）。
 *
 * <p>每当 {@code fashion_consultant} 工具被调用时，本次对话会被记录到此表。
 * 与 {@code chat_messages} 不同，此表<strong>只存储穿搭相关对话</strong>，
 * 保证后续偏好提取的信噪比为 100%。
 *
 * <p>三段式写入：
 * <ol>
 *   <li>管道开始时：写入 userId / userInput / scene / season / formality</li>
 *   <li>管道结束时：回填 recommendation（最终推荐方案摘要）</li>
 *   <li>用户反馈时：回填 userFeedback（"喜欢方案2" / "不喜欢裙子" 等）</li>
 * </ol>
 *
 * @param id             自增主键，由数据库生成
 * @param userId         微信用户 ID
 * @param userInput      用户原始穿搭需求
 * @param scene          QueryAnalyzer 提取的场景（wedding/work/beach 等）
 * @param season         季节（spring/summer/autumn/winter）
 * @param formality      正式度 1-5
 * @param recommendation 最终推荐方案摘要（Coordinator 输出的精炼方案）
 * @param userFeedback   用户后续反馈（空字符串表示尚无反馈）
 * @param embedding      本次对话的向量（JSON 格式的 float 数组，空字符串表示未生成）
 * @param createdAt      创建时间
 */
public record FashionConversation(
        Long id,
        String userId,
        String userInput,
        String scene,
        String season,
        int formality,
        String recommendation,
        String userFeedback,
        String embedding,
        String createdAt
) {
}
