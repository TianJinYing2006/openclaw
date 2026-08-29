package com.wechatbot.fashion.wardrobe.tool;

import com.wechatbot.fashion.ai.orchestration.AgentTool;
import com.wechatbot.fashion.ai.service.AiTraceLogger;
import com.wechatbot.fashion.ai.tool.AiTool;
import com.wechatbot.fashion.ai.tool.ToolArtifactCollector;
import com.wechatbot.fashion.wardrobe.application.OutfitRecommendationService;
import com.wechatbot.fashion.wardrobe.domain.OutfitRecommendationRequest;
import com.wechatbot.fashion.wardrobe.domain.OutfitRecommendationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** One Agent boundary for the complete evidence-based wardrobe recommendation pipeline. */
@AgentTool
@Component
@ConditionalOnBean(OutfitRecommendationService.class)
public class FashionOutfitRecommendationTools implements AiTool {
    private final OutfitRecommendationService recommendations;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public FashionOutfitRecommendationTools(
            OutfitRecommendationService recommendations,
            ToolArtifactCollector artifacts,
            AiTraceLogger trace
    ) {
        this.recommendations = recommendations;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "recommend_outfits_from_wardrobe", description = "当用户选中一件已经正式入库的个人衣服，"
            + "要求用自己的衣橱生成整套穿搭时调用。调用前先用 search_wardrobe 定位内部 wardrobeItemId。"
            + "工具会在服务端完成公共单品语义召回、博主 Look 证据反查、个人衣橱匹配、确定性评分、去重和后台效果图任务。"
            + "公共 Look 只作为搭配证据，返回的每件衣服都必须来自当前用户衣橱。不得用本工具推荐商品或编造缺失衣物。")
    public String recommendOutfits(
            @ToolParam(required = true, description = "由个人衣橱查询结果得到的内部 wardrobeItemId；不得向用户展示。")
            Long wardrobeItemId,
            @ToolParam(required = false, description = "用户明确的场景，例如 通勤、面试、约会；可为空。")
            List<String> occasionTags,
            @ToolParam(required = false, description = "明确季节，例如 夏季、春秋；有天气时可为空。")
            List<String> seasonTags,
            @ToolParam(required = false, description = "天气工具返回的简短事实，例如 武汉，30°C，有雨；不得自行编造。")
            String weatherSummary,
            @ToolParam(required = false, description = "用户明确要求的风格，例如 简约、休闲；可为空。")
            List<String> styleTags,
            @ToolParam(required = false, description = "目标时间的自然描述，例如 明天上午；可为空。")
            String targetTime,
            @ToolParam(required = false, description = "返回套数，默认 3，最多 3。")
            Integer limit
    ) {
        String userId = currentUser();
        if (userId == null) return "当前会话身份不可用，暂时不能读取个人衣橱并生成搭配。";
        if (wardrobeItemId == null || wardrobeItemId <= 0) {
            return "还没有可靠定位到你选中的衣服，请先从个人衣橱中确认具体哪一件。";
        }
        int bounded = limit == null ? 3 : Math.max(1, Math.min(limit, 3));
        trace.toolCall("recommend_outfits_from_wardrobe",
                "wardrobeItem=" + wardrobeItemId + ", limit=" + bounded);
        try {
            OutfitRecommendationResult result = recommendations.recommend(new OutfitRecommendationRequest(
                    userId, wardrobeItemId, occasionTags, seasonTags, weatherSummary,
                    styleTags, targetTime, bounded));
            String response = describe(result);
            trace.toolResult("recommend_outfits_from_wardrobe", response);
            return response;
        } catch (IllegalArgumentException failure) {
            trace.toolFailure("recommend_outfits_from_wardrobe", failure);
            return "没有找到这件已入库且带有可用图片的个人衣服，请重新确认你指的是哪一件。";
        } catch (RuntimeException failure) {
            trace.toolFailure("recommend_outfits_from_wardrobe", failure);
            return "穿搭推荐服务暂时不可用，没有创建虚假的推荐结果，请稍后再试。";
        }
    }

    private static String describe(OutfitRecommendationResult result) {
        if (result.options().isEmpty()) {
            OutfitRecommendationResult.MissingItem missing = result.missingItem();
            return missing == null || missing.summary().isBlank()
                    ? "根据现有证据和个人衣橱，暂时无法形成可靠的完整搭配。"
                    : missing.summary() + "。这是基于现有搭配证据得出的缺口，不会用公共素材冒充你的衣服。";
        }
        StringBuilder response = new StringBuilder("已用你的真实衣橱生成 ")
                .append(result.options().size())
                .append(" 套候选，公共 Look 只用于提供搭配证据：\n");
        for (OutfitRecommendationResult.Option option : result.options()) {
            response.append("第 ").append(option.rank()).append(" 套：")
                    .append(option.displaySummary())
                    .append("；主要依据：").append(reasons(option.scoreBreakdown()))
                    .append("。\n");
        }
        response.append("无人物搭配效果图已放到后台处理，完成后会主动发到微信。"
                + "现在只需简短告诉用户已经生成哪些方案，不要声称图片已经发送，也不要展示任何内部编号或分数。");
        return response.toString();
    }

    private static String reasons(Map<String, Double> breakdown) {
        if (breakdown == null || breakdown.isEmpty()) return "个人衣橱可用性";
        List<Map.Entry<String, Double>> ranked = breakdown.entrySet().stream()
                .filter(entry -> entry.getValue() != null && Double.isFinite(entry.getValue()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(3).toList();
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, Double> entry : ranked) {
            String label = switch (entry.getKey()) {
                case "evidence" -> "真实搭配证据";
                case "occasion" -> "场景适配";
                case "seasonWeather" -> "季节与天气";
                case "color" -> "颜色协调";
                case "style" -> "风格一致";
                case "fitFormality" -> "版型与正式度";
                case "preference" -> "个人偏好";
                case "novelty" -> "减少近期重复";
                default -> "";
            };
            if (!label.isBlank() && !labels.contains(label)) labels.add(label);
        }
        return labels.isEmpty() ? "个人衣橱可用性" : String.join("、", labels);
    }

    private String currentUser() {
        String userId = artifacts.userId();
        return userId == null || userId.isBlank() || "unknown".equals(userId) ? null : userId;
    }
}
