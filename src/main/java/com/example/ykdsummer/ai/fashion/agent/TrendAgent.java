package com.example.ykdsummer.ai.fashion.agent;

import com.example.ykdsummer.ai.fashion.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trend Agent（趋势分析师）。
 *
 * <p>职责：验证每套方案是否符合 2026 年潮流趋势。
 * 与 Critic Agent 并行执行，互不依赖。
 */
@Component
public class TrendAgent {

    private static final Logger log = LoggerFactory.getLogger(TrendAgent.class);
    // 测试阶段放宽限制，后续完善后再收紧
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_TOKENS = 1500;

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
            return TrendOutput.neutralFor(stylist);
        }

        output = normalizeOutput(output, stylist);
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

    private TrendOutput normalizeOutput(TrendOutput output, StylistOutput stylist) {
        if (output == null || output.trendAnalysis() == null) {
            return TrendOutput.neutralFor(stylist);
        }

        Map<Integer, TrendOutput.TrendReview> bySuggestionId = new LinkedHashMap<>();
        for (TrendOutput.TrendReview review : output.trendAnalysis()) {
            if (review != null && review.suggestionId() > 0) {
                bySuggestionId.put(review.suggestionId(), review);
            }
        }

        List<TrendOutput.TrendReview> normalized = new ArrayList<>();
        for (StylistOutput.OutfitSuggestion suggestion : stylist.suggestions()) {
            TrendOutput.TrendReview review = bySuggestionId.get(suggestion.id());
            normalized.add(review != null
                    ? normalizeReview(review)
                    : TrendOutput.neutralReview(suggestion.id()));
        }
        return new TrendOutput(normalized);
    }

    private TrendOutput.TrendReview normalizeReview(TrendOutput.TrendReview review) {
        return new TrendOutput.TrendReview(
                review.suggestionId(),
                clampScore(review.trendScore()),
                hasText(review.seasonalMatch()) ? review.seasonalMatch() : "基本匹配",
                nonEmpty(review.trendingElements(), "基础流行元素待确认"),
                nonEmpty(review.datedElements(), "无明显过时元素"),
                hasText(review.searchSummary()) ? review.searchSummary() : "趋势分析摘要缺失，按中性依据处理。"
        );
    }

    private int clampScore(int score) {
        if (score == 0) {
            return 3;
        }
        return Math.max(1, Math.min(5, score));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> nonEmpty(List<String> values, String fallback) {
        if (values == null || values.isEmpty()) {
            return List.of(fallback);
        }
        return values;
    }
}
