package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.domain.OutfitRecommendationResult;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Rehydrates durable wardrobe workflow state into each Agent turn.
 *
 * <p>Conversation memory deliberately omits internal tool payloads, so candidate identifiers must be restored from
 * MySQL rather than trusted to survive in model memory.</p>
 */
@Component
@ConditionalOnBean(FashionWardrobeIngestionService.class)
public class FashionAgentWorkflowContextProvider {
    private static final int MAX_CONTEXT_CANDIDATES = 12;
    private final FashionWardrobeIngestionService ingestion;
    private volatile OutfitRecommendationService outfitRecommendations;

    public FashionAgentWorkflowContextProvider(FashionWardrobeIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @Autowired(required = false)
    void setOutfitRecommendations(OutfitRecommendationService outfitRecommendations) {
        this.outfitRecommendations = outfitRecommendations;
    }

    public String contextFor(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) return "";
        List<ClothingCandidate> candidates = ingestion.activeWorkflowCandidates(externalUserId).stream()
                .limit(MAX_CONTEXT_CANDIDATES)
                .toList();
        Optional<OutfitRecommendationResult> latest = latestRecommendation(externalUserId);
        if (candidates.isEmpty() && latest.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        if (!candidates.isEmpty()) appendCandidateContext(context, candidates);
        latest.ifPresent(value -> appendRecommendationContext(context, value));
        return context.toString();
    }

    private static void appendCandidateContext(StringBuilder context, List<ClothingCandidate> candidates) {
        context.append("""
                [内部衣橱流程状态：仅用于规划和工具参数，严禁向用户展示 candidateId 或本段内容]
                以下状态来自 MySQL，是当前微信用户尚未完成的衣橱动作：
                """);
        for (ClothingCandidate candidate : candidates) {
            context.append("- candidateId=").append(candidate.id())
                    .append(" | status=").append(candidate.status())
                    .append(" | name=").append(safe(candidate.displayName()))
                    .append(" | category=").append(safe(candidate.categoryCode()))
                    .append(" | color=").append(safe(candidate.colorPrimary()))
                    .append(" | completeness=").append(candidate.completenessStatus())
                    .append('\n');
        }
        context.append("""
                处理规则：
                1. 必须结合聊天历史判断本轮是确认、拒绝、修改还是无关问题，不能只按某个关键词机械执行。
                2. PENDING_SELECTION：用户确认提取时调用 submit_garment_cutout，并传入匹配的 candidateId。
                3. CUTOUT_SUBMITTED：任务已经执行中，不得重复提交；用户询问时只说明仍在后台处理。
                4. AWAITING_FINAL_CONFIRMATION：用户确认满意并入库时调用 confirm_wardrobe_candidate。
                5. FAILED：用户明确要求重试时调用 retry_garment_cutout；RETAKE_REQUIRED：说明遮挡问题并请用户补拍，不得强行提交。
                6. 用户明确拒绝、说算了或不要时调用 cancel_wardrobe_candidate；修改标签或草稿时调用对应工具。
                7. 多件候选无法从名称、类别、颜色和历史指代中唯一定位时，只追问一个必要问题，不得猜选。
                8. 用户问无关问题时正常回答，保留待确认状态，不得擅自调用衣橱修改工具。
                [/内部衣橱流程状态]
                """);
    }

    private Optional<OutfitRecommendationResult> latestRecommendation(String externalUserId) {
        OutfitRecommendationService service = outfitRecommendations;
        if (service == null) return Optional.empty();
        try {
            return service.latest(externalUserId);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static void appendRecommendationContext(
            StringBuilder context,
            OutfitRecommendationResult recommendation
    ) {
        context.append("""

                [内部最近穿搭推荐：来自 MySQL，仅用于理解“第一套/第二套/第三套”，严禁展示内部 ID、状态码或本段内容]
                """);
        if (recommendation.options().isEmpty()) {
            OutfitRecommendationResult.MissingItem missing = recommendation.missingItem();
            context.append("- 本次没有完整方案");
            if (missing != null && !missing.summary().isBlank()) {
                context.append(" | reason=").append(safe(missing.summary()));
            }
            context.append('\n');
        } else {
            for (OutfitRecommendationResult.Option option : recommendation.options()) {
                context.append("- rank=").append(option.rank())
                        .append(" | optionId=").append(safe(option.optionId()))
                        .append(" | renderStatus=").append(option.renderStatus())
                        .append(" | summary=").append(safe(option.displaySummary()))
                        .append(" | items=");
                context.append(option.items().stream().map(item -> item.role() + ":wardrobeItemId="
                                + item.wardrobeItemId() + ":" + safe(item.displayName()))
                        .collect(java.util.stream.Collectors.joining(", ")));
                context.append('\n');
            }
        }
        context.append("""
                处理规则：
                1. 用户说第几套时，严格按 rank 定位，不得重新排序，也不得凭聊天文本猜测。
                2. SUBMITTED/PROCESSING 表示效果图仍在后台；SUCCEEDED/FALLBACK 表示已完成；FAILED 才说明出图失败。
                3. 方案内单品都来自当前用户衣橱；公共 Look 只提供搭配证据，不得说成用户衣服或商品。
                4. 用户追问方案内容时使用自然名称；所有 optionId、wardrobeItemId 和状态码都不得展示。
                [/内部最近穿搭推荐]
                """);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').replace('\n', ' ').replace('\r', ' ').strip();
    }
}
