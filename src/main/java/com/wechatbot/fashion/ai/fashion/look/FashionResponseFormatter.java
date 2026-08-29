package com.wechatbot.fashion.ai.fashion.look;

import com.wechatbot.fashion.ai.fashion.look.model.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 穿搭推荐结果格式化器。
 *
 * <p>将 AgentCoordinator 的结构化输出转换为微信友好的自然语言文案。
 */
@Component
public class FashionResponseFormatter {

    private static final int MAX_TIPS = 3;
    private static final int MAX_ALTERNATIVES = 2;

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

        CoordinatorOutput coord = result.coordinator();
        if (coord == null) {
            return formatSafetyFallback(result);
        }

        // 最终穿搭方案
        String scene = sceneDisplayName(result.analyzedQuery());
        sb.append("这套更适合").append(scene).append("：\n\n");

        CoordinatorOutput.RefinedOutfit outfit = coord.refinedOutfit();
        if (outfit != null) {
            appendLine(sb, "上衣", outfit.top());
            appendLine(sb, "下装", outfit.bottom());
            appendLine(sb, "鞋子", outfit.shoes());
            appendLine(sb, "配饰", outfit.accessories());
        }

        // 推荐理由
        if (coord.finalReasoning() != null && !coord.finalReasoning().isBlank()) {
            sb.append("\n为什么这样穿：").append(compact(coord.finalReasoning(), 120)).append("\n");
        }

        // 实用建议
        List<String> tips = coord.practicalTips();
        if (tips != null && !tips.isEmpty()) {
            sb.append("\n小建议：\n");
            for (int i = 0; i < Math.min(tips.size(), MAX_TIPS); i++) {
                sb.append(i + 1).append(". ").append(compact(tips.get(i), 60)).append("\n");
            }
        }

        // 备选方案（展示 Stylist 的其他方案）
        if (result.stylist() != null && !result.stylist().isEmpty()
                && result.stylist().suggestions().size() > 1) {
            sb.append("\n想换个感觉的话，还可以选：\n");
            int shown = 0;
            for (StylistOutput.OutfitSuggestion s : result.stylist().suggestions()) {
                if (coord.finalRecommendation() != null
                        && s.id() == coord.finalRecommendation().selectedSuggestionId()) {
                    continue;
                }
                if (shown >= MAX_ALTERNATIVES) {
                    break;
                }
                sb.append("- ").append(nonBlank(s.styleLabel(), "备选风格"));
                if (s.reasoning() != null && !s.reasoning().isBlank()) {
                    sb.append("：").append(compact(s.reasoning(), 60));
                }
                sb.append("\n");
                shown++;
            }
        }

        // 末尾附上可试穿的方案编号，供 LLM 在用户要求"试穿推荐的那套"时，
        // 通过 virtual_try_on_reference_outfit 传入正确的 referenceOutfitId（图文对齐依赖同一编号）。
        String outfitId = referenceOutfitId(coord, result.stylist());
        if (outfitId != null && !outfitId.isBlank()) {
            sb.append("\n（可试穿方案编号：").append(outfitId.strip()).append("）");
        }

        return sb.toString().strip();
    }

    /** 优先取 Coordinator 最终方案引用的 outfit 编号，缺失时回退到 Stylist 第一套方案。 */
    private static String referenceOutfitId(CoordinatorOutput coord, StylistOutput stylist) {
        if (coord != null && coord.refinedOutfit() != null) {
            String value = coord.refinedOutfit().referenceOutfitId();
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        if (stylist != null && stylist.suggestions() != null && !stylist.suggestions().isEmpty()) {
            String value = stylist.suggestions().getFirst().referenceOutfitId();
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 将推荐结果压缩为简短摘要（用于存储到 fashion_conversations 表）。
     *
     * <p>格式示例："上衣：白色T恤 | 下装：牛仔裤 | 鞋子：运动鞋 | 理由：简约百搭"
     */
    public String summarize(FashionResult result) {
        if (result == null || result.coordinator() == null) {
            return "";
        }
        CoordinatorOutput coord = result.coordinator();
        StringBuilder sb = new StringBuilder();

        CoordinatorOutput.RefinedOutfit outfit = coord.refinedOutfit();
        if (outfit != null) {
            if (outfit.top() != null && !outfit.top().isBlank()) {
                sb.append("上衣:").append(compact(outfit.top(), 40));
            }
            if (outfit.bottom() != null && !outfit.bottom().isBlank()) {
                if (!sb.isEmpty()) sb.append(" | ");
                sb.append("下装:").append(compact(outfit.bottom(), 40));
            }
            if (outfit.shoes() != null && !outfit.shoes().isBlank()) {
                if (!sb.isEmpty()) sb.append(" | ");
                sb.append("鞋:").append(compact(outfit.shoes(), 30));
            }
        }

        if (coord.finalReasoning() != null && !coord.finalReasoning().isBlank()) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("理由:").append(compact(coord.finalReasoning(), 60));
        }

        return sb.toString();
    }

    /**
     * 安全兜底文案（所有 Agent 均失败时）。
     */
    private String formatSafetyFallback(FashionResult result) {
        String scene = sceneDisplayName(result == null ? null : result.analyzedQuery());

        return "为你推荐一套通用" + scene + "穿搭方案：\n\n"
                + "上衣：白色基础T恤\n"
                + "下装：直筒牛仔裤\n"
                + "鞋子：白色运动鞋\n"
                + "配饰：简约帆布包\n\n"
                + "这是一套百搭安全的方案，适合大多数" + scene + "场景。"
                + "如果你有更具体的需求，可以告诉我场景和风格偏好。";
    }

    private String sceneDisplayName(AnalyzedQuery query) {
        if (query == null || query.params() == null) {
            return "日常";
        }
        String scene = query.params().scene();
        if (scene == null) {
            return "日常";
        }
        return switch (scene.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "FORMAL_EVENT", "FORMAL", "WEDDING" -> "正式场合";
            case "WORKPLACE", "COMMUTE", "WORK", "BUSINESS" -> "通勤";
            case "SCHOOL" -> "校园";
            case "TRAVEL", "TRIP" -> "旅行";
            case "OUTDOOR", "SPORT", "BEACH" -> "户外";
            case "DATE" -> "约会";
            case "DAILY" -> "日常";
            default -> "日常";
        };
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append("：").append(value.strip()).append("\n");
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String compact(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, Math.max(0, maxLength - 1)) + "…";
    }
}
