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
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AgentLlmCaller llmCaller;

    public QueryAnalyzer(AgentLlmCaller llmCaller) {
        this.llmCaller = llmCaller;
    }

    /**
     * 分析用户需求。
     *
     * @param userInput 用户原始输入
     * @return 结构化查询分析结果，LLM 失败时返回关键词兜底结果
     */
    public AnalyzedQuery analyze(String userInput) {
        AnalyzedQuery result = llmCaller.callAgent(
                AgentPrompts.QUERY_ANALYZER,
                userInput,
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
}
