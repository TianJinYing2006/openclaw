package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiUsageProperties;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/** 把一条微信消息按复杂度分成三档，并为两个 OpenAI 兼容协议生成同一种预算对象。 */
@Component
public class TokenBudgetPolicy {
    private static final long SYSTEM_AND_PROTOCOL_OVERHEAD = 400L;
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
        AiRequestBudget.TaskClass taskClass = classify(safePrompt, safeHistory.size(), safeImages.size(), safeFiles.size());
        int maxOutput = switch (taskClass) {
            case SIMPLE_TEXT -> properties.getSimpleMaxOutputTokens();
            case STANDARD -> properties.getStandardMaxOutputTokens();
            case COMPLEX_OR_MULTIMODAL -> properties.getComplexMaxOutputTokens();
        };
        return new AiRequestBudget(taskClass, maxOutput, estimatedInput, estimatedInput + maxOutput);
    }

    private AiRequestBudget.TaskClass classify(String prompt, int historyCount, int imageCount, int fileCount) {
        if (imageCount > 0 || fileCount > 0 || historyCount > 8 || prompt.length() > 600) {
            return AiRequestBudget.TaskClass.COMPLEX_OR_MULTIMODAL;
        }
        if (historyCount == 0 && prompt.length() <= properties.getSimplePromptCharacters()) {
            return AiRequestBudget.TaskClass.SIMPLE_TEXT;
        }
        return AiRequestBudget.TaskClass.STANDARD;
    }
    private static long ceilDivide(long value, long divisor) { return value <= 0 ? 0L : (value + divisor - 1) / divisor; }
}
