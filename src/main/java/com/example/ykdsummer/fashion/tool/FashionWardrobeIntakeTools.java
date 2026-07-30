package com.example.ykdsummer.fashion.tool;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.application.FashionItemNamer;
import com.example.ykdsummer.fashion.application.FashionWardrobeIngestionService;
import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.domain.ClothingCandidateLabels;
import com.example.ykdsummer.fashion.domain.ClothingCandidateStatus;
import com.example.ykdsummer.fashion.domain.ClothingCompletenessStatus;
import com.example.ykdsummer.fashion.domain.GarmentCutoutTask;
import com.example.ykdsummer.fashion.domain.GarmentDraftVersion;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** User-confirmed photo intake workflow: analyze -> review/edit -> cutout -> final wardrobe confirmation. */
@Component
@ConditionalOnBean(FashionWardrobeIngestionService.class)
public class FashionWardrobeIntakeTools implements AiTool {
    private final FashionWardrobeIngestionService intake;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public FashionWardrobeIntakeTools(FashionWardrobeIngestionService intake, ToolArtifactCollector artifacts,
                                      AiTraceLogger trace) {
        this.intake = intake;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "analyze_wardrobe_photo", description = "仅当用户明确要求识别、提取或把已上传服装照片加入衣橱时调用。"
            + "必须先获得 img_ 图片编号。工具会识别一张图中的多个可用单品并检查完整度；不能因为用户只发图而自动保存。"
            + "衣服穿在人身上、被手或其他衣物轻微遮挡时仍可识别；只有主要轮廓或类别无法可靠判断时才返回重拍要求。")
    public String analyzeWardrobePhoto(
            @ToolParam(description = "服装照片编号，必须来自当前或已保存的 img_ 图片。") String imageAssetId,
            @ToolParam(required = false, description = "图片版本；为空时使用最新版本。") Integer imageVersion
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("analyze_wardrobe_photo", "hasImage=true");
        try {
            FashionWardrobeIngestionService.IntakeResult result = intake.analyzePhoto(userId, imageAssetId, imageVersion);
            String message = describeCandidates(result.summary(), result.candidates(), result.reusedExistingDrafts());
            trace.toolResult("analyze_wardrobe_photo", message);
            return message;
        } catch (IllegalArgumentException failure) {
            return failed("analyze_wardrobe_photo", failure, "服装识别失败：请确认图片编号属于当前用户。");
        } catch (RuntimeException failure) {
            return failed("analyze_wardrobe_photo", failure, "服装识别暂时不可用，请稍后重试或重新拍摄清晰单品图。");
        }
    }

    @Tool(name = "list_wardrobe_photo_candidates", description = "当用户询问刚才照片识别出了什么，"
            + "或准备选择、修改某个候选单品时调用。返回的 candidateId 是内部关联键，绝不可在微信回复中展示。")
    public String listWardrobePhotoCandidates(
            @ToolParam(description = "原始服装照片的 img_ 编号。") String imageAssetId,
            @ToolParam(required = false, description = "图片版本；为空时使用最新版本。") Integer imageVersion
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            List<ClothingCandidate> values = intake.candidatesForPhoto(userId, imageAssetId, imageVersion);
            String message = describeCandidates("", values, true);
            trace.toolResult("list_wardrobe_photo_candidates", message);
            return message;
        } catch (RuntimeException failure) {
            return failed("list_wardrobe_photo_candidates", failure, "读取服装候选失败，请稍后重试。");
        }
    }

    @Tool(name = "update_wardrobe_candidate_labels", description = "仅当用户明确要求修改尚未入库的服装候选标签时调用，"
            + "例如颜色、类别、名称、版型、风格、季节、材质、图案或适用场景。candidateId 是内部关联键；"
            + "用户刚收到且仅有一张待确认草稿时可留空，工具会自动定位。"
            + "没有提及的字段必须保留原值，不能猜测或清空。")
    public String updateWardrobeCandidateLabels(
            @ToolParam(required = false, description = "内部服装候选编号 UUID；不要向用户索要或展示。当前只有一张待确认草稿时可为空。") String candidateId,
            @ToolParam(required = false, description = "新的显示名称；未提及则为空。") String displayName,
            @ToolParam(required = false, description = "新的标准类目，例如 T_SHIRT、JACKET、JEANS、SHOES；未提及则为空。") String categoryCode,
            @ToolParam(required = false, description = "新的主色；未提及则为空。") String colorPrimary,
            @ToolParam(required = false, description = "新的次要颜色列表；未提及传 null。") List<String> secondaryColors,
            @ToolParam(required = false, description = "新的风格标签列表；未提及传 null。") List<String> styleTags,
            @ToolParam(required = false, description = "新的版型；未提及则为空。") String fitCode,
            @ToolParam(required = false, description = "新的季节标签列表；未提及传 null。") List<String> seasonTags,
            @ToolParam(required = false, description = "新的材质，例如 COTTON、DENIM、KNIT；未提及则为空。") String material,
            @ToolParam(required = false, description = "新的图案，例如 SOLID、STRIPED、CHECKED、PRINTED；未提及则为空。") String patternCode,
            @ToolParam(required = false, description = "新的适用场景标签，例如 CASUAL、COMMUTE、DATE；未提及传 null。") List<String> occasionTags
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            String resolvedCandidateId = resolveMutableCandidateId(userId, candidateId);
            ClothingCandidate current = intake.candidate(userId, resolvedCandidateId)
                    .orElseThrow(() -> new IllegalArgumentException("Candidate is not available"));
            ClothingCandidateLabels labels = new ClothingCandidateLabels(
                    choose(displayName, current.displayName()), choose(categoryCode, current.categoryCode()),
                    choose(colorPrimary, current.colorPrimary()), secondaryColors == null ? current.secondaryColors() : secondaryColors,
                    styleTags == null ? current.styleTags() : styleTags, choose(fitCode, current.fitCode()),
                    seasonTags == null ? current.seasonTags() : seasonTags, safe(material), safe(patternCode), occasionTags);
            ClothingCandidate updated = intake.updateCandidateLabels(userId, resolvedCandidateId, labels);
            String message = "草稿标签已更新：\n" + intake.draftReviewSummary(updated)
                    + "\n请根据图片和标签继续确认或提出调整。";
            trace.toolResult("update_wardrobe_candidate_labels", message);
            return message;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return failed("update_wardrobe_candidate_labels", failure, "修改标签失败：请确认候选编号和当前状态。");
        } catch (RuntimeException failure) {
            return failed("update_wardrobe_candidate_labels", failure, "修改标签失败，请稍后重试。");
        }
    }

    @Tool(name = "submit_garment_cutout", description = "仅当用户已经查看识别候选并明确选择要提取的单品时调用。"
            + "只可提交完整度 READY 的候选；任务在后台执行，完成后会主动发送草稿图、识别属性和最终确认提示。"
            + "用户尚未确认、候选要求重拍或只是询问时不可调用。当前仅有一个待选候选时 candidateIds 可为空，工具会安全恢复。")
    public String submitGarmentCutout(
            @ToolParam(required = false, description = "用户明确选中的一个或多个完整候选 UUID；唯一待选候选时可为空。")
            List<String> candidateIds
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            List<String> resolvedIds = resolveSelectionCandidateIds(userId, candidateIds);
            List<GarmentCutoutTask> tasks = intake.selectCandidatesForCutout(userId, resolvedIds);
            String message = "已提交 " + tasks.size() + " 件衣物的草稿生成。完成后会自动把抠图和识别属性发到微信，"
                    + "用户确认后才会加入衣橱。";
            trace.toolResult("submit_garment_cutout", message);
            return message;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return failed("submit_garment_cutout", failure, "提交抠图失败：请确认用户已选择完整、未遮挡的候选单品。");
        } catch (RuntimeException failure) {
            return failed("submit_garment_cutout", failure, "提交抠图失败，请稍后重试。");
        }
    }

    @Tool(name = "cancel_wardrobe_candidate", description = "仅当用户明确拒绝、取消或说不要继续处理尚未入库的衣物时调用。"
            + "会取消尚未完成的抠图任务并关闭候选，不会删除已经正式加入衣橱的单品。"
            + "candidateIds 来自内部衣橱流程状态；当前只有一个活动候选时可为空。")
    public String cancelWardrobeCandidate(
            @ToolParam(required = false, description = "要取消的内部候选 UUID 列表；唯一活动候选时可为空。")
            List<String> candidateIds
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            List<String> resolvedIds = resolveActiveCandidateIds(userId, candidateIds);
            int cancelled = intake.cancelCandidates(userId, resolvedIds);
            String message = cancelled == 0
                    ? "这些衣物候选已经取消，不会继续处理。"
                    : "已取消 " + cancelled + " 件待处理衣物，不会加入衣橱。";
            trace.toolResult("cancel_wardrobe_candidate", message);
            return message;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return failed("cancel_wardrobe_candidate", failure, "取消失败：请说明要取消哪件尚未入库的衣物。");
        } catch (RuntimeException failure) {
            return failed("cancel_wardrobe_candidate", failure, "取消衣物处理失败，请稍后重试。");
        }
    }

    @Tool(name = "retry_garment_cutout", description = "仅当用户明确要求重新抠图，或对刚完成的抠图提出具体修改时调用。"
            + "candidateId 是内部关联键；用户刚收到且仅有一张待确认草稿时可留空。instruction 只描述边缘、完整度或背景等"
            + "抠图修正，不得改变衣服颜色、长度或设计。")
    public String retryGarmentCutout(
            @ToolParam(required = false, description = "内部服装候选编号 UUID；当前只有一张待确认草稿时可为空。") String candidateId,
            @ToolParam(required = false, description = "用户提出的抠图修正，例如 保留完整裤脚；为空表示普通重试。") String instruction
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            String resolvedCandidateId = resolveRetryCandidateId(userId, candidateId);
            GarmentCutoutTask task = intake.retryCutout(userId, resolvedCandidateId, safe(instruction));
            String message = "已重新提交衣物草稿生成。完成后会自动发送新草稿。";
            trace.toolResult("retry_garment_cutout", message);
            return message;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return failed("retry_garment_cutout", failure, "重新抠图失败：该候选当前不能重试，或需要先重新拍摄完整照片。");
        } catch (RuntimeException failure) {
            return failed("retry_garment_cutout", failure, "重新抠图失败，请稍后重试。");
        }
    }

    @Tool(name = "list_garment_draft_versions", description = "当用户想比较、查看、选择或确认某件衣物的不同草稿版本时调用。"
            + "一个衣物草稿可以保留原版和多次修改后的多个可选版本；返回的版本序号可供后续预览、继续修改和确认。"
            + "candidateId 是内部关联键；仅有一件待确认衣物时可为空。")
    public String listGarmentDraftVersions(
            @ToolParam(required = false, description = "内部服装候选编号 UUID；当前仅一件待确认草稿时可为空。") String candidateId
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            String resolvedCandidateId = resolveReviewCandidateId(userId, candidateId);
            List<GarmentDraftVersion> values = intake.draftVersions(userId, resolvedCandidateId);
            if (values.isEmpty()) return "这件衣物还没有生成可查看的草稿版本。";
            String message = values.stream().map(this::describeVersion)
                    .reduce((left, right) -> left + "\n" + right).orElse("这件衣物还没有生成可查看的草稿版本。")
                    + "\n用户可以说“看第二版”“基于第一版再改长一点”或“确认第二版加入衣橱”。";
            trace.toolResult("list_garment_draft_versions", message);
            return message;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return failed("list_garment_draft_versions", failure, "读取衣物草稿版本失败：请先等待抠图完成，或说明要查看哪件衣物。");
        }
    }

    @Tool(name = "preview_garment_draft_version", description = "仅当用户要求看某一个衣物草稿版本时调用。"
            + "会把选中的版本图片随本轮微信消息发回；candidateId 是内部关联键，当前只有一件待确认草稿时可为空。")
    public String previewGarmentDraftVersion(
            @ToolParam(required = false, description = "内部服装候选编号 UUID；当前仅一件待确认草稿时可为空。") String candidateId,
            @ToolParam(description = "用户要查看的草稿版本序号，从 1 开始。") int versionNumber
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            String resolvedCandidateId = resolveReviewCandidateId(userId, candidateId);
            StoredImage image = intake.draftPreviewImage(userId, resolvedCandidateId, versionNumber);
            artifacts.add(AiArtifact.image(readDraftImage(userId, resolvedCandidateId, versionNumber),
                    "衣物草稿第" + versionNumber + "版", image.assetId(), image.version()));
            String message = "已回传衣物草稿第" + versionNumber + "版。";
            trace.toolResult("preview_garment_draft_version", message);
            return message;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return failed("preview_garment_draft_version", failure, "读取该衣物草稿版本失败：请先查看可选版本。");
        }
    }

    @Tool(name = "edit_garment_draft", description = "仅当用户明确要求修改已完成衣物草稿本身时调用，"
            + "例如衣长、裤长、边缘、颜色或展示效果。它会以选定草稿图为参考，后台生成新的可选版本，"
            + "不会覆盖或删除旧版本。candidateId 是内部关联键；仅有一件待确认草稿时可为空。"
            + "sourceVersionNumber 为空时以当前最新版为基础。")
    public String editGarmentDraft(
            @ToolParam(required = false, description = "内部服装候选编号 UUID；当前仅一件待确认草稿时可为空。") String candidateId,
            @ToolParam(required = false, description = "作为参考的草稿版本序号；为空时使用当前最新版。") Integer sourceVersionNumber,
            @ToolParam(description = "用户希望保留和调整的具体视觉要求。") String instruction
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            String resolvedCandidateId = resolveReviewCandidateId(userId, candidateId);
            intake.reviseDraft(userId, resolvedCandidateId, sourceVersionNumber, safe(instruction));
            String message = "已基于选定衣物草稿提交新版本生成。旧版本会继续保留供比较；新版本完成后会自动发到微信。";
            trace.toolResult("edit_garment_draft", message);
            return message;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return failed("edit_garment_draft", failure, "修改衣物草稿失败：请先等待一版草稿生成完成，再说明想怎么调整。");
        } catch (RuntimeException failure) {
            return failed("edit_garment_draft", failure, "修改衣物草稿失败，请稍后重试。");
        }
    }

    @Tool(name = "confirm_wardrobe_candidate", description = "仅当用户明确确认一张已完成的衣物抠图满意并要求加入衣橱时调用。"
            + "candidateId 是内部关联键；用户刚收到且仅有一张待确认草稿时必须留空，让工具自动定位。"
            + "不可在抠图前或用户未确认时调用。")
    public String confirmWardrobeCandidate(
            @ToolParam(required = false, description = "内部服装候选编号 UUID；当前只有一张待确认草稿时留空。") String candidateId,
            @ToolParam(required = false, description = "用户确认的草稿版本序号；为空时确认当前最新版。") Integer versionNumber
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            String resolvedCandidateId = resolveReviewCandidateId(userId, candidateId);
            ClothingCandidate candidate = intake.candidate(userId, resolvedCandidateId)
                    .orElseThrow(() -> new IllegalArgumentException("Candidate is not available"));
            WardrobeItem item = versionNumber == null || versionNumber < 1
                    ? intake.confirmCandidate(userId, resolvedCandidateId)
                    : intake.confirmCandidate(userId, resolvedCandidateId, versionNumber);
            String message = "已加入个人衣橱。\n" + intake.draftReviewSummary(candidate)
                    + "\n后续搭配会优先检索这件单品。";
            trace.toolResult("confirm_wardrobe_candidate", message);
            return message;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return failed("confirm_wardrobe_candidate", failure, "确认入衣橱失败：请先等待抠图完成，并确认候选编号正确。");
        } catch (RuntimeException failure) {
            return failed("confirm_wardrobe_candidate", failure, "确认入衣橱失败，请稍后重试。");
        }
    }

    private String currentUser() {
        String userId = artifacts.userId();
        return userId == null || userId.isBlank() || "unknown".equals(userId) ? null : userId;
    }
    private String failed(String name, RuntimeException failure, String message) {
        trace.toolFailure(name, failure);
        return message;
    }
    private static String unavailable() { return "当前会话身份不可用，暂时不能管理个人衣橱。"; }
    private String resolveReviewCandidateId(String userId, String candidateId) {
        if (!safe(candidateId).isBlank()) return safe(candidateId);
        List<ClothingCandidate> values = intake.awaitingFinalConfirmationCandidates(userId);
        if (values.size() == 1) return values.getFirst().id();
        if (values.isEmpty()) throw new IllegalStateException("No completed wardrobe draft is awaiting confirmation");
        throw new IllegalStateException("More than one wardrobe draft is awaiting confirmation");
    }
    private String resolveMutableCandidateId(String userId, String candidateId) {
        if (!safe(candidateId).isBlank()) return safe(candidateId);
        List<ClothingCandidate> values = new java.util.ArrayList<>(intake.pendingSelectionCandidates(userId));
        values.addAll(intake.awaitingFinalConfirmationCandidates(userId));
        List<ClothingCandidate> distinct = values.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ClothingCandidate::id,
                        value -> value,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();
        if (distinct.size() == 1) return distinct.getFirst().id();
        if (distinct.isEmpty()) throw new IllegalStateException("No mutable wardrobe candidate is available");
        throw new IllegalStateException("More than one mutable wardrobe candidate is available");
    }
    private String resolveRetryCandidateId(String userId, String candidateId) {
        if (!safe(candidateId).isBlank()) return safe(candidateId);
        List<ClothingCandidate> values = intake.activeWorkflowCandidates(userId).stream()
                .filter(value -> value.completenessStatus() == ClothingCompletenessStatus.READY)
                .filter(value -> value.status() == ClothingCandidateStatus.FAILED
                        || value.status() == ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION)
                .toList();
        if (values.size() == 1) return values.getFirst().id();
        if (values.isEmpty()) throw new IllegalStateException("No wardrobe candidate can be retried");
        throw new IllegalStateException("More than one wardrobe candidate can be retried");
    }
    private List<String> resolveSelectionCandidateIds(String userId, List<String> candidateIds) {
        List<String> requested = candidateIds == null ? List.of() : candidateIds.stream()
                .filter(value -> !safe(value).isBlank()).map(FashionWardrobeIntakeTools::safe).distinct().toList();
        if (!requested.isEmpty()) return requested;
        List<ClothingCandidate> values = intake.pendingSelectionCandidates(userId);
        if (values.size() == 1) return List.of(values.getFirst().id());
        if (values.isEmpty()) throw new IllegalStateException("No clothing candidate is waiting for selection");
        throw new IllegalStateException("More than one clothing candidate is waiting for selection");
    }
    private List<String> resolveActiveCandidateIds(String userId, List<String> candidateIds) {
        List<String> requested = candidateIds == null ? List.of() : candidateIds.stream()
                .filter(value -> !safe(value).isBlank()).map(FashionWardrobeIntakeTools::safe).distinct().toList();
        if (!requested.isEmpty()) return requested;
        List<ClothingCandidate> values = intake.activeWorkflowCandidates(userId);
        if (values.size() == 1) return List.of(values.getFirst().id());
        if (values.isEmpty()) throw new IllegalStateException("No active wardrobe candidate is available");
        throw new IllegalStateException("More than one active wardrobe candidate is available");
    }
    private static String choose(String requested, String fallback) { return safe(requested).isBlank() ? fallback : safe(requested); }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
    private byte[] readDraftImage(String userId, String candidateId, int versionNumber) {
        return intake.draftPreviewBytes(userId, candidateId, versionNumber);
    }
    private String describeVersion(GarmentDraftVersion value) {
        String kind = value.instruction() == null || value.instruction().isBlank() ? "初始抠图" : "按要求调整";
        return "第" + value.versionNumber() + "版：" + kind + (value.current() ? "（当前最新版）" : "");
    }
    private static String describeCandidates(String summary, List<ClothingCandidate> candidates, boolean reused) {
        if (candidates == null || candidates.isEmpty()) {
            return "没有识别到可可靠提取的单品。请补拍目标衣物的大部分轮廓，确保类别、颜色和主要形状清晰可见。";
        }
        StringBuilder message = new StringBuilder();
        if (!safe(summary).isBlank()) message.append(summary).append('\n');
        message.append("内部候选（仅用于后续工具调用，严禁向用户展示编号）：\n");
        candidates.forEach(candidate -> message.append("- ").append(describe(candidate)).append('\n'));
        message.append("用户只需用名称或描述选择单品；如标签不对，可先修改。只有确认选择后才能提交抠图。");
        return message.toString().strip();
    }
    private static String describe(ClothingCandidate candidate) {
        StringBuilder line = new StringBuilder("candidateId=").append(candidate.id()).append(" | ")
                .append(FashionItemNamer.nameFor(candidate.categoryCode(), candidate.colorPrimary(),
                        candidate.displayName() + " " + candidate.analysisAttributesJson(), candidate.fitCode(), ""));
        line.append(" | ").append(candidate.completenessStatus() == ClothingCompletenessStatus.READY ? "可提交抠图" : "需要补拍");
        if (!safe(candidate.retakeGuidance()).isBlank()) line.append(" | ").append(candidate.retakeGuidance());
        return line.toString();
    }
}
