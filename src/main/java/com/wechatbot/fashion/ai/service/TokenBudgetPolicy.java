package com.wechatbot.fashion.ai.service;

import com.wechatbot.fashion.ai.config.AiUsageProperties;
import com.wechatbot.fashion.ai.model.AiFile;
import com.wechatbot.fashion.ai.model.AiImage;
import com.wechatbot.fashion.ai.model.ConversationMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** 把一条微信消息按复杂度分成三档，并为两个 OpenAI 兼容协议生成同一种预算对象。 */
@Component
public class TokenBudgetPolicy {
    private static final long SYSTEM_AND_PROTOCOL_OVERHEAD = 400L;
    private static final List<String> ROUTE_PLANNING_KEYWORDS = List.of(
            "路线", "导航", "怎么走", "如何到达", "怎么去", "公交", "地铁", "驾车", "开车",
            "步行", "骑行", "起点", "终点", "换乘", "路程", "route", "navigation", "directions",
            "driving", "walking", "cycling", "transit"
    );
    private final AiUsageProperties properties;

    public TokenBudgetPolicy(AiUsageProperties properties) { this.properties = properties; }

    public AiRequestBudget plan(List<ConversationMessage> history, String prompt, List<AiImage> images, List<AiFile> files) {
        List<ConversationMessage> safeHistory = history == null ? List.of() : history;
        List<AiImage> safeImages = images == null ? List.of() : images;
        List<AiFile> safeFiles = files == null ? List.of() : files;
        String safePrompt = prompt == null ? "" : prompt;
        long textCharacters = safePrompt.codePointCount(0, safePrompt.length());
        for (ConversationMessage message : safeHistory) {
            if (message != null && message.text() != null) {
                textCharacters += message.text().codePointCount(0, message.text().length());
            }
        }
        long estimatedInput = SYSTEM_AND_PROTOCOL_OVERHEAD + ceilDivide(textCharacters, 2);
        estimatedInput += (long) safeImages.size() * properties.getEstimatedTokensPerImage();
        for (AiFile file : safeFiles) {
            if (file != null && file.bytes() != null) estimatedInput += ceilDivide(file.bytes().length, 4);
        }
        if (!properties.isEnabled()) {
            return new AiRequestBudget(AiRequestBudget.TaskClass.STANDARD, 0, estimatedInput, estimatedInput);
        }
        AiRequestBudget.TaskClass taskClass = classify(safePrompt, safeHistory.size(), safeImages.size(), safeFiles.size());
        int maxOutput = switch (taskClass) {
            case SIMPLE_TEXT -> properties.getSimpleMaxOutputTokens();
            case STANDARD -> properties.getStandardMaxOutputTokens();
            case COMPLEX_OR_MULTIMODAL -> properties.getComplexMaxOutputTokens();
        };
        return new AiRequestBudget(taskClass, maxOutput, estimatedInput, estimatedInput + maxOutput);
    }

    private AiRequestBudget.TaskClass classify(String prompt, int historyCount, int imageCount, int fileCount) {
        if (imageCount > 0 || fileCount > 0 || historyCount > 8 || prompt.length() > 600 || isRoutePlanningRequest(prompt)) {
            return AiRequestBudget.TaskClass.COMPLEX_OR_MULTIMODAL;
        }
        if (historyCount == 0 && prompt.length() <= properties.getSimplePromptCharacters()) {
            return AiRequestBudget.TaskClass.SIMPLE_TEXT;
        }
        return AiRequestBudget.TaskClass.STANDARD;
    }

    private static boolean isRoutePlanningRequest(String prompt) {
        String normalizedPrompt = prompt.toLowerCase(Locale.ROOT);
        return ROUTE_PLANNING_KEYWORDS.stream().anyMatch(normalizedPrompt::contains);
    }

    private static long ceilDivide(long value, long divisor) { return value <= 0 ? 0L : (value + divisor - 1) / divisor; }
}
