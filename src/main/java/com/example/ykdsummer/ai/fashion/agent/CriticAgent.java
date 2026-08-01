package com.example.ykdsummer.ai.fashion.agent;

import com.example.ykdsummer.ai.fashion.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Critic Agent（严格评审师）。
 *
 * <p>职责：对 Stylist 生成的每套方案进行多维度评分，必须指出不足。
 * 与 Trend Agent 并行执行，互不依赖。
 */
@Component
public class CriticAgent {

    private static final Logger log = LoggerFactory.getLogger(CriticAgent.class);
    // 测试阶段放宽限制，后续完善后再收紧
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_TOKENS = 2500;

    private final AgentLlmCaller llmCaller;
    private final ObjectMapper objectMapper;

    public CriticAgent(AgentLlmCaller llmCaller, ObjectMapper objectMapper) {
        this.llmCaller = llmCaller;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行方案评审。
     *
     * @param stylist Stylist 的方案列表
     * @param query   查询参数（用于判断场合适配）
     * @return 评审意见，失败返回 empty
     */
    public CriticOutput execute(StylistOutput stylist, AnalyzedQuery query) {
        if (stylist == null || stylist.isEmpty()) {
            return CriticOutput.empty();
        }

        String userMessage = buildUserMessage(stylist, query);

        CriticOutput output = llmCaller.callAgent(
                AgentPrompts.CRITIC,
                userMessage,
                CriticOutput.class,
                MAX_TOKENS,
                TIMEOUT
        );

        if (output == null) {
            log.warn("Critic Agent returned null, using empty fallback");
            return CriticOutput.empty();
        }

        // suggestionId 一致性校验
        if (output.reviews() != null) {
            java.util.Set<Integer> expectedIds = stylist.suggestions().stream()
                    .map(s -> s.id())
                    .collect(java.util.stream.Collectors.toSet());
            for (CriticOutput.Critique critique : output.reviews()) {
                if (!expectedIds.contains(critique.suggestionId())) {
                    log.warn("Critic returned unexpected suggestionId: {}, expected one of {}",
                            critique.suggestionId(), expectedIds);
                }
            }
        }

        log.info("Critic Agent reviewed {} suggestions", output.reviews() != null ? output.reviews().size() : 0);
        return output;
    }

    private String buildUserMessage(StylistOutput stylist, AnalyzedQuery query) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 待评审的穿搭方案\n");
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
