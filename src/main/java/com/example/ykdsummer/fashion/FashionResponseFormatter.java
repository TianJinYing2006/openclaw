package com.example.ykdsummer.fashion;

import com.example.ykdsummer.fashion.model.FashionResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Formats the fashion pipeline result into concise WeChat-friendly copy.
 */
@Component
public class FashionResponseFormatter {

    public String format(FashionResult result) {
        StringBuilder message = new StringBuilder(512);
        message.append("穿搭建议｜").append(orDefault(result.scene(), "今日出门")).append("\n\n");
        message.append("最终方案\n");
        appendLine(message, result.finalOutfit());

        if (hasText(result.reason())) {
            message.append("\n推荐理由\n");
            appendLine(message, result.reason());
        }

        appendSection(message, "实用建议", result.practicalTips());
        appendSection(message, "备选风格", result.alternatives());
        appendSection(message, "注意避雷", result.warnings());

        if (hasText(result.traceSummary())) {
            message.append("\n小结\n");
            appendLine(message, result.traceSummary());
        }
        return message.toString().trim();
    }

    private static void appendSection(StringBuilder message, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        message.append("\n").append(title).append("\n");
        for (String value : values) {
            if (hasText(value)) {
                appendLine(message, value);
            }
        }
    }

    private static void appendLine(StringBuilder message, String value) {
        message.append("- ").append(orDefault(value, "暂无补充")).append("\n");
    }

    private static String orDefault(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
