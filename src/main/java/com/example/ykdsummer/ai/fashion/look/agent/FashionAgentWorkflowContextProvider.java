package com.example.ykdsummer.ai.fashion.look.agent;

import com.example.ykdsummer.ai.fashion.look.profile.FashionConversationService;
import com.example.ykdsummer.common.fashion.FashionWorkflowContextProvider;
import com.example.ykdsummer.fashion.wardrobe.application.FashionWardrobeIngestionService;
import com.example.ykdsummer.fashion.wardrobe.application.OutfitRecommendationService;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRecommendationResult;
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
public class FashionAgentWorkflowContextProvider implements FashionWorkflowContextProvider {
    private static final int MAX_CONTEXT_CANDIDATES = 12;
    private final FashionWardrobeIngestionService ingestion;
    private volatile OutfitRecommendationService outfitRecommendations;
    private volatile FashionConversationService conversations;

    public FashionAgentWorkflowContextProvider(FashionWardrobeIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @Autowired(required = false)
    public void setOutfitRecommendations(OutfitRecommendationService outfitRecommendations) {
        this.outfitRecommendations = outfitRecommendations;
    }

    @Autowired(required = false)
    public void setConversations(FashionConversationService conversations) {
        this.conversations = conversations;
    }

    public String contextFor(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) return "";
        List<ClothingCandidate> candidates = ingestion.activeWorkflowCandidates(externalUserId).stream()
                .limit(MAX_CONTEXT_CANDIDATES)
                .toList();
        Optional<OutfitRecommendationResult> latest = latestRecommendation(externalUserId);
        if (candidates.isEmpty() && latest.isEmpty() && latestReferenceOutfit(externalUserId) == null) return "";

        StringBuilder context = new StringBuilder();
        if (!candidates.isEmpty()) appendCandidateContext(context, candidates);
        latest.ifPresent(value -> appendRecommendationContext(context, value));
        appendReferenceOutfitContext(context, externalUserId);
        return context.toString();
    }

    /** 最近一次穿搭推荐命中的 outfit 编号（用于"试穿"指代消解）；无记录返回 null。 */
    private String latestReferenceOutfit(String externalUserId) {
        FashionConversationService service = conversations;
        if (service == null) return null;
        return service.findLatestReferenceOutfit(externalUserId);
    }

    /** 注入最近一次参考穿搭推荐方案，约束"试穿"必须复用该编号而不是重新 consult。 */
    private void appendReferenceOutfitContext(StringBuilder context, String externalUserId) {
        String outfitId = latestReferenceOutfit(externalUserId);
        if (outfitId == null || outfitId.isBlank()) return;
        context.append("""
                [内部最近穿搭推荐方案：来自 MySQL，仅用于理解"试穿/这套/这套衣服"的指代，严禁向用户展示编号或本段内容]
                最近一次推荐方案的 outfit 编号=""").append(outfitId).append("""
                ，用户刚才看到的参考图片就是该方案。
                处理规则：
                1. 用户刚获得该推荐方案后，本轮说"试穿/穿一下/试试/上身效果"等表达且未明确指向衣橱单品（未提"衣柜/衣橱里的"）时，
                   默认指这套最近推荐方案，必须用该编号调用 virtual_try_on_reference_outfit，不得只口头承诺试穿。
                2. 若用户最近一步操作是加入/预览衣橱单品（见[内部衣橱流程状态]），说"试穿一下/穿一下/试试"等未指明出处的表达，默认指刚处理的衣橱单品，
                   必须调用 virtual_try_on_wardrobe_item，绝不调用本工具，也不要从历史对话中自行挑选其他 outfit 编号。
                3. 不得为了试穿重新调用 fashion_consultant（那会生成一套新方案并发来新图片）；只有从未推荐过任何方案时才调用 fashion_consultant 获取。
                [/内部最近穿搭推荐方案]
                """);
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
                3. CUTOUT_SUBMITTED：任务已经执行中，不得重复提交；用户询问是否完成时调用 list_garment_draft_versions 查询持久化状态。
                4. AWAITING_FINAL_CONFIRMATION：用户确认满意并入库时调用 confirm_wardrobe_candidate。
                5. FAILED：用户明确要求重试时调用 retry_garment_cutout；RETAKE_REQUIRED：说明遮挡问题并请用户补拍，不得强行提交。
                6. “基于原图/重新提取”调用 retry_garment_cutout；“基于第几版/当前版继续改”调用 edit_garment_draft，普通草稿编辑只传一张选定草稿图。
                7. 用户要求视觉修改但没有说明基于原始照片还是某个草稿版本时，先调用 list_garment_draft_versions 获取可选版本，再只追问这一个来源问题。
                8. 如果上一轮刚追问修改来源，用户只回复“原图”“当前版”或“第N版”，必须结合紧邻历史中的未执行修改要求调用对应工具，不得丢失原修改要求或重复追问。
                9. 用户明确拒绝、说算了或不要时调用 cancel_wardrobe_candidate；修改标签时调用标签工具。
                10. 多件候选无法从名称、类别、颜色和历史指代中唯一定位时，只追问一个必要问题，不得猜选。
                11. 用户问无关问题时正常回答，保留待确认状态，不得擅自调用衣橱修改工具。
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
