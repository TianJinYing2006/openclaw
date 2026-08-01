package com.example.ykdsummer.ai.fashion.rag;

import com.example.ykdsummer.ai.fashion.agent.AgentLlmCaller;
import com.example.ykdsummer.ai.fashion.agent.AgentPrompts;
import com.example.ykdsummer.ai.fashion.model.AnalyzedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 查询分析器：将用户模糊需求分解为结构化参数。
 *
 * <p>使用 LLM 做意图理解和参数提取。LLM 失败时降级为关键词匹配兜底。
 * 这是穿搭管道的第一步，分析结果用于 RAG 检索和所有 Agent 的上下文。
 */
@Component
public class QueryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(QueryAnalyzer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final AgentLlmCaller llmCaller;

    public QueryAnalyzer(AgentLlmCaller llmCaller) {
        this.llmCaller = llmCaller;
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
