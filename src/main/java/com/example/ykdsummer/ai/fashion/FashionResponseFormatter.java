package com.example.ykdsummer.ai.fashion;

import com.example.ykdsummer.ai.fashion.model.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 穿搭推荐结果格式化器。
 *
 * <p>将 AgentCoordinator 的结构化输出转换为微信友好的自然语言文案。
 */
@Component
public class FashionResponseFormatter {

    /**
     * 将 FashionResult 格式化为微信回复文案。
     */
    public String format(FashionResult result) {
        if (result == null) {
            return "抱歉，穿搭推荐服务暂时遇到了问题。";
        }

        if (result.degraded() && result.coordinator() == null) {
            return formatSafetyFallback(result);
        }

        StringBuilder sb = new StringBuilder();

        // 降级提示（仅开发调试用，可注释掉）
        if (result.degraded()) {
            sb.append("（").append(result.errorMessage()).append("）\n\n");
        }

        CoordinatorOutput coord = result.coordinator();
        if (coord == null) {
            return formatSafetyFallback(result);
        }

        // 最终穿搭方案
        sb.append("为你推荐以下穿搭方案\n\n");

        CoordinatorOutput.RefinedOutfit outfit = coord.refinedOutfit();
        if (outfit != null) {
            sb.append("上衣：").append(safe(outfit.top())).append("\n");
            sb.append("下装：").append(safe(outfit.bottom())).append("\n");
            sb.append("鞋子：").append(safe(outfit.shoes())).append("\n");
            if (outfit.accessories() != null && !outfit.accessories().isBlank()) {
                sb.append("配饰：").append(outfit.accessories()).append("\n");
            }
        }

        // 推荐理由
        if (coord.finalReasoning() != null && !coord.finalReasoning().isBlank()) {
            sb.append("\n推荐理由：").append(coord.finalReasoning()).append("\n");
        }

        // 实用建议
        List<String> tips = coord.practicalTips();
        if (tips != null && !tips.isEmpty()) {
            sb.append("\n实用建议：\n");
            for (int i = 0; i < tips.size(); i++) {
                sb.append(i + 1).append(". ").append(tips.get(i)).append("\n");
            }
        }

        // 备选方案（展示 Stylist 的其他方案）
        if (result.stylist() != null && !result.stylist().isEmpty()
                && result.stylist().suggestions().size() > 1) {
            sb.append("\n其他备选风格：\n");
            for (StylistOutput.OutfitSuggestion s : result.stylist().suggestions()) {
                if (coord.finalRecommendation() != null
                        && s.id() == coord.finalRecommendation().selectedSuggestionId()) {
                    continue;
                }
                sb.append("- ").append(safe(s.styleLabel()));
                if (s.reasoning() != null && !s.reasoning().isBlank()) {
                    sb.append("：").append(s.reasoning());
                }
                sb.append("\n");
            }
        }

        return sb.toString().strip();
    }

    /**
     * 安全兜底文案（所有 Agent 均失败时）。
     */
    private String formatSafetyFallback(FashionResult result) {
        String scene = "日常";
        if (result.analyzedQuery() != null && result.analyzedQuery().params() != null) {
            scene = sceneDisplayName(result.analyzedQuery().params().scene());
        }

        return "为你推荐一套通用" + scene + "穿搭方案：\n\n"
                + "上衣：白色基础T恤\n"
                + "下装：直筒牛仔裤\n"
                + "鞋子：白色运动鞋\n"
                + "配饰：简约帆布包\n\n"
                + "这是一套百搭安全的方案，适合大多数" + scene + "场景。"
                + "如果你有更具体的需求，可以告诉我场景和风格偏好。";
    }

    private String sceneDisplayName(String scene) {
        if (scene == null) return "日常";
        return switch (scene) {
            case "wedding" -> "婚礼";
            case "date" -> "约会";
            case "work" -> "通勤";
            case "beach" -> "海边";
            case "sport" -> "运动";
            case "travel" -> "旅行";
            default -> "日常";
        };
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
