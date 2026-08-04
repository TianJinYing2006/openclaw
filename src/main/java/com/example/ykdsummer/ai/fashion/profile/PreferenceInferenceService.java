package com.example.ykdsummer.ai.fashion.profile;

import com.example.ykdsummer.ai.fashion.agent.AgentLlmCaller;
import com.example.ykdsummer.ai.fashion.agent.AgentPrompts;
import com.example.ykdsummer.ai.fashion.model.InferredPreference;
import com.example.ykdsummer.fashion.application.FashionCoreService;
import com.example.ykdsummer.fashion.domain.FashionPreferenceUpdate;
import java.math.BigDecimal;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 偏好推断服务：把用户对穿搭推荐的反馈，转成规范化偏好写入
 * {@code fashion_user_preferences}，供衣橱推荐排序引擎
 * （OutfitRecommendationEngine.preferenceMatch）加权使用。
 *
 * <p>这是用户画像的"断链补全"：存储层（upsertPreference）和消费层（preferenceMatch）
 * 早已就绪，唯独缺少"从原始反馈推断偏好维度/值/极性"的写入环节。</p>
 *
 * <p>设计：LLM few-shot 抽取（维度约束与排序引擎一致），失败或空结果静默返回，
 * 不打断穿搭主流程；偏好写入异常只记日志。</p>
 */
@Component
public class PreferenceInferenceService {

    private static final Logger log = LoggerFactory.getLogger(PreferenceInferenceService.class);

    /** 偏好抽取是轻量调用，短超时控制反馈路径延迟。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** LLM 推断出的偏好权重；confidence 0.8 表示推断而非用户直述。 */
    private static final BigDecimal WEIGHT = BigDecimal.ONE;
    private static final BigDecimal CONFIDENCE = new BigDecimal("0.8");
    private static final String SOURCE = "FEEDBACK_INFERENCE";
    private static final int MAX_EVIDENCE = 200;

    private final AgentLlmCaller llmCaller;
    private volatile FashionCoreService coreService;

    public PreferenceInferenceService(AgentLlmCaller llmCaller) {
        this.llmCaller = llmCaller;
    }

    /** 持久化未启用时保持可用（偏好写入自动跳过）。 */
    @Autowired(required = false)
    public void setCoreService(FashionCoreService coreService) {
        this.coreService = coreService;
    }

    /**
     * 从反馈文本抽取偏好并写入用户画像；LLM 失败、解析为空或无明确偏好时静默返回。
     *
     * @param userId      微信用户 ID
     * @param feedbackText 用户反馈原文
     * @param sentiment   情感倾向（POSITIVE/NEGATIVE/MIXED，来自反馈检测）
     */
    public void inferAndRecord(String userId, String feedbackText, String sentiment) {
        if (userId == null || userId.isBlank() || feedbackText == null || feedbackText.isBlank()) {
            return;
        }
        InferredPreference[] inferred;
        try {
            inferred = llmCaller.callAgent(
                    AgentPrompts.PREFERENCE_INFERENCE,
                    buildUserMessage(feedbackText, sentiment),
                    InferredPreference[].class,
                    300,
                    TIMEOUT
            );
        } catch (RuntimeException exception) {
            log.debug("Preference inference LLM call failed: {}", exception.getMessage());
            return;
        }
        if (inferred == null || inferred.length == 0) {
            log.debug("No preference inferred from feedback for user {}", anonymize(userId));
            return;
        }

        FashionCoreService service = coreService;
        if (service == null) {
            log.debug("FashionCoreService unavailable (persistence disabled), skip preference recording");
            return;
        }
        String evidence = feedbackText.length() <= MAX_EVIDENCE
                ? feedbackText : feedbackText.substring(0, MAX_EVIDENCE) + "…";
        int recorded = 0;
        for (InferredPreference preference : inferred) {
            if (!preference.valid()) {
                continue;
            }
            try {
                service.updatePreference(userId, new FashionPreferenceUpdate(
                        preference.dimension(),
                        preference.value(),
                        preference.polarity(),
                        WEIGHT,
                        CONFIDENCE,
                        SOURCE,
                        evidence
                ));
                recorded++;
            } catch (RuntimeException exception) {
                log.warn("Failed to record preference {} for user {}: {}",
                        preference, anonymize(userId), exception.getMessage());
            }
        }
        log.info("Recorded {} preference(s) from feedback for user {}", recorded, anonymize(userId));
    }

    private static String buildUserMessage(String feedbackText, String sentiment) {
        String safeSentiment = sentiment == null || sentiment.isBlank() ? "UNKNOWN" : sentiment;
        return "用户反馈：" + feedbackText + "\n情感倾向：" + safeSentiment;
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
