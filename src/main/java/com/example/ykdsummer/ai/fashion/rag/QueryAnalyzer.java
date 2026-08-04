package com.example.ykdsummer.ai.fashion.rag;

import com.example.ykdsummer.ai.fashion.agent.AgentLlmCaller;
import com.example.ykdsummer.ai.fashion.agent.AgentPrompts;
import com.example.ykdsummer.ai.fashion.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.model.FeedbackDetection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 查询分析器：将用户模糊需求分解为结构化参数，并识别对上次推荐的反馈。
 *
 * <p>使用 LLM 做意图理解、参数提取和反馈识别。LLM 失败时降级为关键词匹配兜底。
 * 这是穿搭管道的第一步，分析结果用于 RAG 检索和所有 Agent 的上下文。
 */
@Component
public class QueryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(QueryAnalyzer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** 反馈分类是轻量调用（100 tokens），单独用更短超时控制延迟，失败回退关键词。 */
    private static final Duration FEEDBACK_TIMEOUT = Duration.ofSeconds(15);

    /** 反馈关键词兜底（仅当 LLM 分类失败时使用）。 */
    private static final List<String> FEEDBACK_SIGNALS = List.of(
            "喜欢", "不喜欢", "满意", "不满意", "好看", "不好看",
            "太正式", "太休闲", "换一", "换个", "换成", "改一下", "不合适"
    );

    private final AgentLlmCaller llmCaller;

    public QueryAnalyzer(AgentLlmCaller llmCaller) {
        this.llmCaller = llmCaller;
    }

    /**
     * 判断用户输入是否为对上一次推荐的反馈，并识别情感倾向。
     *
     * <p>使用 few-shot LLM 分类替代硬编码关键词；LLM 失败时回退关键词兜底。
     *
     * @param userInput 用户原始输入
     * @return 反馈检测结果（isFeedback + sentiment）
     */
    public FeedbackDetection detectFeedback(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return FeedbackDetection.notFeedback();
        }
        FeedbackDetection detection = llmCaller.callAgent(
                AgentPrompts.FEEDBACK_DETECTOR,
                userInput,
                FeedbackDetection.class,
                100,
                FEEDBACK_TIMEOUT
        );
        if (detection != null) {
            log.info("Feedback classification: isFeedback={}, sentiment={}, input={}",
                    detection.isFeedback(), detection.sentiment(), userInput);
            return detection;
        }
        log.warn("Feedback classification LLM failed, using keyword fallback");
        boolean keywordHit = FEEDBACK_SIGNALS.stream().anyMatch(userInput::contains);
        return keywordHit ? new FeedbackDetection(true, "UNKNOWN") : FeedbackDetection.notFeedback();
    }

    /**
     * 分析用户需求（无画像注入，用于非穿搭场景或冷启动）。
     */
    public AnalyzedQuery analyze(String userInput) {
        return analyze(userInput, "");
    }

    /**
     * 分析用户需求（注入用户画像上下文）。
     *
     * @param userInput     用户原始输入
     * @param profileContext 用户偏好上下文（可为空字符串）
     * @return 结构化查询分析结果，LLM 失败时返回关键词兜底结果
     */
    public AnalyzedQuery analyze(String userInput, String profileContext) {
        String contextualInput = buildContextualInput(userInput, profileContext);
        AnalyzedQuery result = llmCaller.callAgent(
                AgentPrompts.QUERY_ANALYZER,
                contextualInput,
                AnalyzedQuery.class,
                200,
                TIMEOUT
        );

        if (result == null) {
            log.warn("QueryAnalyzer LLM failed, using keyword fallback");
            return AnalyzedQuery.fallback(userInput);
        }

        log.info("QueryAnalyzer: scene={}, season={}, formality={}",
                result.params() != null ? result.params().scene() : "null",
                result.params() != null ? result.params().season() : "null",
                result.params() != null ? result.params().formality() : 0);
        return result;
    }

    private static String buildContextualInput(String userInput, String profileContext) {
        if (profileContext != null && !profileContext.isBlank()) {
            return profileContext + "\n" + userInput;
        }
        return userInput;
    }
}
