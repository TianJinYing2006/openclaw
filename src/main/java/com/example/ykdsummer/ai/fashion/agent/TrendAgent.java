package com.example.ykdsummer.ai.fashion.agent;

import com.example.ykdsummer.ai.fashion.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Trend Agent（趋势分析师）。
 *
 * <p>职责：验证每套方案是否符合 2026 年潮流趋势。
 * 与 Critic Agent 并行执行，互不依赖。
 */
@Component
public class TrendAgent {

    private static final Logger log = LoggerFactory.getLogger(TrendAgent.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_TOKENS = 400;

    private final AgentLlmCaller llmCaller;
    private final ObjectMapper objectMapper;

    public TrendAgent(AgentLlmCaller llmCaller) {
        this.llmCaller = llmCaller;
        this.objectMapper = new ObjectMapper()
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    /**
     * 执行趋势分析。
     *
     * @param stylist Stylist 的方案列表
     * @param query   查询参数（用于判断季节匹配）
     * @return 趋势分析，失败返回 neutral
     */
    public TrendOutput execute(StylistOutput stylist, AnalyzedQuery query) {
        if (stylist == null || stylist.isEmpty()) {
            return TrendOutput.neutral();
        }

        String userMessage = buildUserMessage(stylist, query);

        TrendOutput output = llmCaller.callAgent(
                AgentPrompts.TREND,
                userMessage,
                TrendOutput.class,
                MAX_TOKENS,
                TIMEOUT
        );

        if (output == null) {
            log.warn("Trend Agent returned null, using neutral fallback");
            return TrendOutput.neutral();
        }

        log.info("Trend Agent analyzed {} suggestions", output.trendAnalysis() != null ? output.trendAnalysis().size() : 0);
        return output;
    }

    private String buildUserMessage(StylistOutput stylist, AnalyzedQuery query) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 待分析的穿搭方案\n");
        try {
            sb.append(objectMapper.writeValueAsString(stylist));
        } catch (Exception e) {
            sb.append("JSON序列化失败");
        }

        if (query != null && query.params() != null) {
            sb.append("\n\n## 用户需求参数\n").append(query.params().toParamString());
        }

        return sb.toString();
    }
}
