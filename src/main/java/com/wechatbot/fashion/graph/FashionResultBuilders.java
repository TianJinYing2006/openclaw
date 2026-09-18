package com.wechatbot.fashion.graph;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CoordinatorOutput;
import com.wechatbot.fashion.ai.fashion.look.model.CriticOutput;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.fashion.look.model.TrendOutput;
import com.wechatbot.fashion.common.fashion.ReferenceImageResolver;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fashion 子图专用的结果构造 / 辅助工具（原自 {@code AgentCoordinator} 抽取，System A 下线后由图独占）。
 */
public final class FashionResultBuilders {

    /** RAG 上下文中的穿搭编号标记，如 [outfit_231]。 */
    private static final Pattern OUTFIT_ID_MARKER = Pattern.compile("\\[outfit_(\\d+)\\]");

    private FashionResultBuilders() {
    }

    /**
     * 简单请求判定：正式度 ≤3 且子查询 ≤3 视为简单，跳过 Critic/Trend/Coordinator。
     */
    public static boolean isSimpleRequest(AnalyzedQuery query) {
        if (query == null || query.params() == null) return true;
        int formality = query.params().formality();
        int subQueries = query.decomposedQueries() == null ? 0 : query.decomposedQueries().size();
        return formality <= 3 && subQueries <= 3;
    }

    /** 用 Stylist 第一个方案构造简易 CoordinatorOutput（简单请求与降级共用）。 */
    public static CoordinatorOutput fromStylist(StylistOutput stylist) {
        StylistOutput.OutfitSuggestion first = stylist.suggestions().get(0);
        CoordinatorOutput.RefinedOutfit refined = new CoordinatorOutput.RefinedOutfit(
                first.outfit() != null ? first.outfit().top() : "",
                first.outfit() != null ? first.outfit().bottom() : "",
                first.outfit() != null ? first.outfit().shoes() : "",
                first.outfit() != null ? first.outfit().accessories() : "",
                first.referenceOutfitId()
        );
        CoordinatorOutput.FinalRecommendation rec = new CoordinatorOutput.FinalRecommendation(
                first.id(), "直接采用 Stylist 首选方案", Map.of()
        );
        return new CoordinatorOutput(rec, refined, first.reasoning(), List.of());
    }

    /**
     * 精简 RAG 上下文供 Coordinator 使用：保留每个 {@code [outfit_XXX]} 编号与
     * 【完整搭配】概要，丢弃【单品详情】等冗长内容，降低最终裁决轮的 prompt 长度与耗时。
     */
    public static String compactRagContext(String ragContext) {
        if (ragContext == null || ragContext.isBlank()) {
            return ragContext;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : ragContext.split("(?=\\[outfit_)")) {
            int detailIdx = part.indexOf("【单品详情】");
            sb.append(detailIdx > 0 ? part.substring(0, detailIdx) : part);
        }
        return sb.toString().strip();
    }

    /** 构造安全兜底结果（所有 Agent 全部失败时使用）。 */
    public static FashionResult safetyFallback(String scene, AnalyzedQuery query) {
        return FashionResult.safetyFallback(scene, query);
    }

    /** 构造降级结果（Coordinator 失败时，用 Stylist 首选方案）。 */
    public static FashionResult degraded(StylistOutput stylist, CriticOutput critic,
                                         TrendOutput trend, String ragContext,
                                         AnalyzedQuery query, String errorMessage) {
        return new FashionResult(
                false, fromStylist(stylist), stylist, critic, trend, ragContext, query,
                errorMessage, true
        );
    }

    /**
     * 校正"试穿"指代消解用的 outfit 编号：Coordinator 可能输出参考图库中不存在的编号
     * （LLM 幻觉），此时回退到 RAG 上下文实际命中的 [outfit_XXX] 编号。
     */
    public static String resolveEffectiveOutfitId(FashionResult result, ReferenceImageResolver resolver) {
        String coordinatorId = extractReferenceOutfitId(result);
        if (hasUsableGarments(coordinatorId, resolver)) {
            return coordinatorId;
        }
        String ragId = result == null ? null : extractOutfitIdFromRagContext(result.ragContext());
        if (ragId != null && hasUsableGarments(ragId, resolver)) {
            return ragId;
        }
        return coordinatorId;
    }

    private static boolean hasUsableGarments(String rawId, ReferenceImageResolver resolver) {
        if (rawId == null || rawId.isBlank() || resolver == null) {
            return false;
        }
        String normalized = normalizeOutfitId(rawId);
        return !normalized.isBlank() && !resolver.garmentsFor(normalized).isEmpty();
    }

    private static String extractOutfitIdFromRagContext(String ragContext) {
        if (ragContext == null || ragContext.isBlank()) {
            return null;
        }
        Matcher matcher = OUTFIT_ID_MARKER.matcher(ragContext);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractReferenceOutfitId(FashionResult result) {
        if (result == null || result.coordinator() == null
                || result.coordinator().refinedOutfit() == null) {
            return "";
        }
        String refId = result.coordinator().refinedOutfit().referenceOutfitId();
        return refId == null ? "" : refId;
    }

    /** 从 RAG 上下文提取全部合法 outfit 编号（规范化，保持出现顺序）。 */
    public static Set<String> validOutfitIds(String ragContext) {
        if (ragContext == null || ragContext.isBlank()) {
            return Set.of();
        }
        Set<String> ids = new java.util.LinkedHashSet<>();
        Matcher matcher = OUTFIT_ID_MARKER.matcher(ragContext);
        while (matcher.find()) {
            String normalized = normalizeOutfitId(matcher.group(1));
            if (!normalized.isBlank()) {
                ids.add(normalized);
            }
        }
        return ids;
    }

    /**
     * 校验并纠正最终方案的 {@code referenceOutfitId}：必须是本次检索上下文里真实存在的编号，
     * 否则回退到上下文首个编号。
     *
     * <p>动机：Stylist/Coordinator 的 LLM 可能从注入的近期对话摘要里复述上一套编号（造成"重新推荐
     * 却给出重复穿搭"），或直接幻觉编号；这里以检索上下文为准做确定性纠正，保证图文与"换一套"语义一致。
     */
    public static FashionResult withValidatedReferenceOutfit(FashionResult result, Set<String> validIds) {
        if (result == null || validIds == null || validIds.isEmpty()
                || result.coordinator() == null || result.coordinator().refinedOutfit() == null) {
            return result;
        }
        CoordinatorOutput coordinator = result.coordinator();
        CoordinatorOutput.RefinedOutfit outfit = coordinator.refinedOutfit();
        String current = normalizeOutfitId(outfit.referenceOutfitId());
        if (!current.isBlank() && validIds.contains(current)) {
            return result;
        }
        String fallback = validIds.iterator().next();
        CoordinatorOutput.RefinedOutfit fixedOutfit = new CoordinatorOutput.RefinedOutfit(
                outfit.top(), outfit.bottom(), outfit.shoes(), outfit.accessories(), fallback);
        CoordinatorOutput fixedCoordinator = new CoordinatorOutput(
                coordinator.finalRecommendation(), fixedOutfit,
                coordinator.finalReasoning(), coordinator.practicalTips());
        return new FashionResult(
                result.success(), fixedCoordinator, result.stylist(), result.critic(), result.trend(),
                result.ragContext(), result.analyzedQuery(), result.errorMessage(), result.degraded());
    }

    /** 将 "002"/"outfit_002"/"[outfit_002]" 统一为 "002"（与 image_urls.json 的 key 格式对齐）。 */
    public static String normalizeOutfitId(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replace("[outfit_", "").replace("outfit_", "").replace("]", "").trim();
        try {
            int num = Integer.parseInt(cleaned);
            return String.format("%03d", num);
        } catch (NumberFormatException e) {
            return cleaned;
        }
    }
}
