package com.example.ykdsummer.ai.fashion.agent;

import com.example.ykdsummer.ai.fashion.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Stylist Agent（创意型形象顾问）。
 *
 * <p>职责：根据用户需求、RAG 知识参考和结构化参数，生成 3 套有风格差异的穿搭方案。
 * 输入为结构化上下文 JSON，输出为 StylistOutput JSON。
 *
 * <p>这是管道的第二步，必须在 Critic/Trend 之前执行。
 */
@Component
public class StylistAgent {

    private static final Logger log = LoggerFactory.getLogger(StylistAgent.class);
    // 测试阶段放宽限制，后续完善后再收紧
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_TOKENS = 4000;

    private final AgentLlmCaller llmCaller;

    public StylistAgent(AgentLlmCaller llmCaller) {
        this.llmCaller = llmCaller;
    }

    /**
     * 执行穿搭方案生成（无画像注入）。
     */
    public StylistOutput execute(FashionRequest request, String ragContext, AnalyzedQuery query) {
        return execute(request, ragContext, query, "");
    }

    /**
     * 执行穿搭方案生成（注入用户画像上下文）。
     *
     * @param request        用户请求
     * @param ragContext     RAG 检索的穿搭知识文本
     * @param query          查询分析结果
     * @param profileContext 用户偏好上下文（可为空字符串）
     * @return 3 套穿搭方案，失败返回 empty
     */
    public StylistOutput execute(FashionRequest request, String ragContext, AnalyzedQuery query,
                                  String profileContext) {
        String userMessage = buildUserMessage(request, ragContext, query, profileContext);

        StylistOutput output = llmCaller.callAgent(
                AgentPrompts.STYLIST,
                userMessage,
                StylistOutput.class,
                MAX_TOKENS,
                TIMEOUT
        );

        if (output == null || output.isEmpty()) {
            log.warn("Stylist Agent returned empty result");
            return StylistOutput.empty();
        }

        // 输出质量校验
        if (output.suggestions().size() < 3) {
            log.warn("Stylist generated only {} suggestions, expected 3", output.suggestions().size());
        }
        long distinctStyles = output.suggestions().stream()
                .map(StylistOutput.OutfitSuggestion::styleLabel)
                .distinct()
                .count();
        if (distinctStyles < output.suggestions().size()) {
            log.warn("Stylist generated duplicate style labels: {}",
                    output.suggestions().stream().map(StylistOutput.OutfitSuggestion::styleLabel).toList());
        }

        log.info("Stylist Agent generated {} suggestions", output.suggestions().size());
        return output;
    }

    private String buildUserMessage(FashionRequest request, String ragContext, AnalyzedQuery query,
                                     String profileContext) {
        StringBuilder sb = new StringBuilder();
        if (profileContext != null && !profileContext.isBlank()) {
            sb.append(profileContext).append("\n\n");
        }
        sb.append("## 用户需求\n").append(request.userInput()).append("\n\n");

        sb.append("## 结构化参数\n");
        if (query != null && query.params() != null) {
            sb.append(query.params().toParamString());
        }
        sb.append("\n\n");

        sb.append(ragContext);

        return sb.toString();
    }
}
