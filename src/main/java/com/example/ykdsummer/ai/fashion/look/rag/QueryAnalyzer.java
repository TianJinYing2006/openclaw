package com.example.ykdsummer.ai.fashion.look.rag;

import com.example.ykdsummer.ai.fashion.look.agent.AgentLlmCaller;
import com.example.ykdsummer.ai.fashion.look.agent.AgentPrompts;
import com.example.ykdsummer.ai.fashion.look.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.look.model.FeedbackDetection;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    /**
     * 需求分析结果缓存：同一用户输入 + 画像上下文在短时间内只分析一次。
     *
     * <p>QueryAnalyzer 用贪婪采样（temperature=0）保证确定性，缓存进一步保证
     * 同一查询在评测/线上多次调用时结果一致，消除 LLM 随机性导致的检索命中率波动
     * （实测同一配置不同天 top-5 从 46.3% 跳到 83.3%）。
     * 反馈检测走独立的 {@link #detectFeedback}，不受此缓存影响。
     */
    private final Cache<String, AnalyzedQuery> analysisCache;

    public QueryAnalyzer(AgentLlmCaller llmCaller) {
        this.llmCaller = llmCaller;
        this.analysisCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build();
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
        String cacheKey = contextualInput;

        AnalyzedQuery cached = analysisCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.info("QueryAnalyzer cache hit for input: {}", truncate(userInput));
            return cached;
        }

        AnalyzedQuery result = analyzeUncached(userInput, contextualInput);
        if (result != null) {
            analysisCache.put(cacheKey, result);
        }
        return result;
    }

    /**
     * 绕过缓存的分析调用（供评测测试验证贪婪采样的确定性）。
     *
     * <p>评测需要每轮都真实调用 LLM，以证明 temperature=0 下同一批查询的命中率稳定；
     * 若走缓存则三轮结果必然相同，无法验证确定性。返回 null 表示 LLM 失败（调用方回退）。
     */
    public AnalyzedQuery analyzeUncached(String userInput, String profileContext) {
        String contextualInput = buildContextualInput(userInput, profileContext);
        return analyzeUncachedInternal(userInput, contextualInput);
    }

    private AnalyzedQuery analyzeUncachedInternal(String userInput, String contextualInput) {
        // 贪婪采样（temperature=0）：检索词构造需要确定性，避免同一查询每次分析不同
        AnalyzedQuery result = llmCaller.callAgentGreedy(
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

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    private static String buildContextualInput(String userInput, String profileContext) {
        if (profileContext != null && !profileContext.isBlank()) {
            return profileContext + "\n" + userInput;
        }
        return userInput;
    }
}
