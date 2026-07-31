package com.example.ykdsummer.ai.fashion.agent;

import com.example.ykdsummer.ai.fashion.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinator Agent（首席搭配师，最终裁决）。
 *
 * <p>职责：综合 Stylist 的方案、Critic 的评审和 Trend 的趋势分析，做出最优选择。
 * 优先级：体型适配 > 场合适配 > 风格偏好 > 趋势匹配。
 *
 * <p>这是管道的最后一步（规则后校验之前）。
 */
@Component
public class CoordinatorAgent {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorAgent.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_TOKENS = 600;

    private final AgentLlmCaller llmCaller;
    private final ObjectMapper objectMapper;

    public CoordinatorAgent(AgentLlmCaller llmCaller) {
        this.llmCaller = llmCaller;
        this.objectMapper = new ObjectMapper()
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    /**
     * 执行最终裁决。
     *
     * @param request  用户请求
     * @param stylist  Stylist 的方案
     * @param critic   Critic 的评审
     * @param trend    Trend 的趋势分析
     * @param ragContext RAG 知识参考
     * @return 最终裁决方案，失败返回 null
     */
    public CoordinatorOutput execute(FashionRequest request, StylistOutput stylist,
                                     CriticOutput critic, TrendOutput trend, String ragContext) {
        if (stylist == null || stylist.isEmpty()) {
            log.warn("Coordinator skipped: no stylist suggestions");
            return null;
        }

        String userMessage = buildUserMessage(request, stylist, critic, trend, ragContext);

        CoordinatorOutput output = llmCaller.callAgent(
                AgentPrompts.COORDINATOR,
                userMessage,
                CoordinatorOutput.class,
                MAX_TOKENS,
                TIMEOUT
        );

        if (output == null) {
            log.warn("Coordinator Agent returned null");
            return null;
        }

        output = normalizeOutput(output, stylist);
        log.info("Coordinator Agent selected suggestion {}",
                output.finalRecommendation() != null
                        ? output.finalRecommendation().selectedSuggestionId() : "null");
        return output;
    }

    private String buildUserMessage(FashionRequest request, StylistOutput stylist,
                                     CriticOutput critic, TrendOutput trend, String ragContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 用户需求\n").append(request.userInput()).append("\n\n");

        sb.append("## Stylist 方案\n");
        try {
            sb.append(objectMapper.writeValueAsString(stylist));
        } catch (Exception e) {
            sb.append("JSON序列化失败");
        }

        sb.append("\n\n## Critic 评审\n");
        if (critic != null && !critic.isEmpty()) {
            try {
                sb.append(objectMapper.writeValueAsString(critic));
            } catch (Exception e) {
                sb.append("JSON序列化失败");
            }
        } else {
            sb.append("（Critic 评审不可用，请自行判断）");
        }

        sb.append("\n\n## Trend 趋势分析\n");
        if (trend != null && !trend.isEmpty()) {
            try {
                sb.append(objectMapper.writeValueAsString(trend));
            } catch (Exception e) {
                sb.append("JSON序列化失败");
            }
        } else {
            sb.append("（趋势分析不可用，请自行判断）");
        }

        sb.append("\n\n").append(ragContext);

        return sb.toString();
    }

    private CoordinatorOutput normalizeOutput(CoordinatorOutput output, StylistOutput stylist) {
        Map<Integer, StylistOutput.OutfitSuggestion> suggestionsById = new LinkedHashMap<>();
        for (StylistOutput.OutfitSuggestion suggestion : stylist.suggestions()) {
            suggestionsById.put(suggestion.id(), suggestion);
        }

        int selectedId = output.finalRecommendation() != null
                ? output.finalRecommendation().selectedSuggestionId()
                : 0;
        if (!suggestionsById.containsKey(selectedId)) {
            selectedId = stylist.suggestions().get(0).id();
        }

        StylistOutput.OutfitSuggestion selected = suggestionsById.get(selectedId);
        StylistOutput.Outfit selectedOutfit = selected.outfit();

        CoordinatorOutput.RefinedOutfit refined = output.refinedOutfit();
        CoordinatorOutput.RefinedOutfit normalizedRefined = new CoordinatorOutput.RefinedOutfit(
                chooseText(refined != null ? refined.top() : null,
                        selectedOutfit != null ? selectedOutfit.top() : ""),
                chooseText(refined != null ? refined.bottom() : null,
                        selectedOutfit != null ? selectedOutfit.bottom() : ""),
                chooseText(refined != null ? refined.shoes() : null,
                        selectedOutfit != null ? selectedOutfit.shoes() : ""),
                chooseText(refined != null ? refined.accessories() : null,
                        selectedOutfit != null ? selectedOutfit.accessories() : "")
        );

        CoordinatorOutput.FinalRecommendation current = output.finalRecommendation();
        CoordinatorOutput.FinalRecommendation normalizedRecommendation = new CoordinatorOutput.FinalRecommendation(
                selectedId,
                chooseText(current != null ? current.selectionReasoning() : null,
                        "按体型、场合、风格和趋势优先级选择该方案。"),
                current != null && current.eliminationNotes() != null
                        ? current.eliminationNotes()
                        : Map.of()
        );

        return new CoordinatorOutput(
                normalizedRecommendation,
                normalizedRefined,
                chooseText(output.finalReasoning(), selected.reasoning()),
                output.practicalTips() != null ? output.practicalTips() : List.of()
        );
    }

    private String chooseText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
