package com.example.ykdsummer.ai.fashion.look;

import com.example.ykdsummer.ai.fashion.look.model.FashionConversation;
import com.example.ykdsummer.ai.fashion.look.model.FeedbackDetection;
import com.example.ykdsummer.ai.fashion.look.profile.FashionConversationService;
import com.example.ykdsummer.ai.fashion.look.profile.PreferenceInferenceService;
import com.example.ykdsummer.ai.fashion.look.rag.QueryAnalyzer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 每轮普通消息的穿搭反馈采集器。
 *
 * <p>反馈消息（"太黑了"、"第二套好看"、"换成白色的吧"）通常不会触发
 * {@code fashion_consultant} 工具，因此原先只挂在工具内部的反馈检测永远跑不到，
 * 导致 {@code fashion_conversations.user_feedback} 与 {@code fashion_user_preferences}
 * 长期为空、用户画像无法储存。本组件挂在主对话路径上补齐这条链路：
 *
 * <ol>
 *   <li>关键词预过滤：命中"喜欢/难看/这套/换成"等反馈信号才继续，普通聊天零开销；</li>
 *   <li>LLM 轻量分类（{@link QueryAnalyzer#detectFeedback}）确认是否为反馈；</li>
 *   <li>写入最近一条穿搭对话的 user_feedback，并把反馈推断为规范化偏好
 *       （{@link PreferenceInferenceService#inferAndRecord}）。</li>
 * </ol>
 *
 * <p>所有失败静默降级，绝不打断主对话流程。
 */
@Component
public class FashionFeedbackRecorder {

    private static final Logger log = LoggerFactory.getLogger(FashionFeedbackRecorder.class);

    /** 命中才跑 LLM 分类的关键词，避免"在吗"这类普通聊天也触发反馈检测。 */
    private static final List<String> FEEDBACK_KEYWORDS = List.of(
            "喜欢", "不喜欢", "好看", "不好看", "难看", "不错", "满意", "不满意",
            "这套", "那套", "第一套", "第二套", "第三套", "重来", "再搭",
            "不合适", "不适合", "太黑", "太素", "太花", "太艳", "太短", "太长", "太土");

    private final QueryAnalyzer queryAnalyzer;
    private final FashionConversationService conversationService;
    private volatile PreferenceInferenceService preferenceInference;
    /** 异步执行池（虚拟线程）；未装配（如单测）时降级为同步执行。 */
    private volatile ExecutorService executor;

    public FashionFeedbackRecorder(QueryAnalyzer queryAnalyzer,
                                   FashionConversationService conversationService) {
        this.queryAnalyzer = queryAnalyzer;
        this.conversationService = conversationService;
    }

    /** 持久化未启用时保持可用（偏好写入自动跳过）。 */
    @Autowired(required = false)
    public void setPreferenceInference(PreferenceInferenceService preferenceInference) {
        this.preferenceInference = preferenceInference;
    }

    /** 复用穿搭 Agent 的虚拟线程池；反馈采集绝不阻塞主对话链路。 */
    @Autowired(required = false)
    public void setExecutor(@Qualifier("fashionAgentParallelExecutor") ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * 检测并记录用户反馈；不是反馈或失败时静默返回。
     *
     * <p>关键词预过滤是 O(1) 同步操作（普通聊天零开销）；命中后才把
     * LLM 分类与写库提交到异步线程池，不阻塞回复主路径。
     *
     * @param userId 微信用户 ID
     * @param text   本轮用户原始文本（含语音转写）
     */
    public void maybeRecord(String userId, String text) {
        if (userId == null || userId.isBlank() || text == null || text.isBlank()) {
            return;
        }
        if (!containsKeyword(text)) {
            return;
        }
        ExecutorService pool = executor;
        if (pool != null) {
            pool.execute(() -> classifyAndRecord(userId, text));
        } else {
            classifyAndRecord(userId, text);
        }
    }

    /** 异步体：LLM 分类 + 写 user_feedback + 偏好推断。 */
    private void classifyAndRecord(String userId, String text) {
        FeedbackDetection detection;
        try {
            detection = queryAnalyzer.detectFeedback(text);
        } catch (RuntimeException failure) {
            log.debug("Feedback classification failed: {}", failure.getMessage());
            return;
        }
        if (detection == null || !detection.isFeedback()) {
            return;
        }
        try {
            FashionConversation latest = conversationService.findLatest(userId);
            if (latest == null) {
                return;
            }
            String feedback = "【" + safe(detection.sentiment()) + "】" + text;
            conversationService.updateFeedback(latest.id(), feedback);
            log.info("Recorded fashion feedback for conversation {}: {}", latest.id(), feedback);
            PreferenceInferenceService inference = preferenceInference;
            if (inference != null) {
                inference.inferAndRecord(userId, text, detection.sentiment());
            }
        } catch (Exception failure) {
            log.debug("Failed to record fashion feedback: {}", failure.getMessage());
        }
    }

    private static boolean containsKeyword(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return FEEDBACK_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
