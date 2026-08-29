package com.example.ykdsummer.fashion.wardrobe.tool;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.orchestration.AgentTool;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.wardrobe.application.FashionItemNamer;
import com.example.ykdsummer.fashion.wardrobe.application.FashionWardrobeIngestionService;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidateLabels;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidateStatus;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCompletenessStatus;
import com.example.ykdsummer.fashion.wardrobe.domain.GarmentCutoutTask;
import com.example.ykdsummer.fashion.wardrobe.domain.GarmentDraftVersion;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItem;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** User-confirmed photo intake workflow: analyze -> review/edit -> cutout -> final wardrobe confirmation. */
@AgentTool
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
            + "必须先获得 img_ 图片编号。工具会把识别任务放入后台：首次调用返回“正在识别”，识别完成后会自动把候选和"
            + "完整度发给用户；同一张照片已有候选时直接返回候选列表。衣服穿在人身上、被手或其他衣物轻微遮挡时仍可识别；"
            + "只有主要轮廓或类别无法可靠判断时才返回重拍要求。整套穿搭照片会作为一个整体候选识别，不拆分单件。"
            + "用户刚发送新照片并说\"加入衣柜/入库/抠图\"时，必须先调用本工具识别这张新照片，再提交抠图；"
            + "严禁跳过识别直接把上一张照片的候选当作新照片的入库目标。")
    public String analyzeWardrobePhoto(
            @ToolParam(description = "服装照片编号，必须来自当前或已保存的 img_ 图片。") String imageAssetId,
            @ToolParam(required = false, description = "图片版本；为空时使用最新版本。") Integer imageVersion
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("analyze_wardrobe_photo", "hasImage=true");
        try {
            List<ClothingCandidate> existing = intake.candidatesForPhoto(userId, imageAssetId, imageVersion);
            if (!existing.isEmpty()) {
                String message = describeCandidates("", existing, true);
                trace.toolResult("analyze_wardrobe_photo", message);
                return message;
            }
            boolean submitted = intake.submitPhotoAnalysis(userId, imageAssetId, imageVersion);
            if (!submitted) {
                List<ClothingCandidate> ready = intake.candidatesForPhoto(userId, imageAssetId, imageVersion);
                if (!ready.isEmpty()) {
                    String message = describeCandidates("", ready, true);
                    trace.toolResult("analyze_wardrobe_photo", message);
                    return message;
                }
                String message = "正在识别图片中，识别完成后会自动把候选和完整度发给你。";
                trace.toolResult("analyze_wardrobe_photo", message);
                return message;
            }
            String message = "正在识别图片中，识别完成后会自动把候选和完整度发给你。";
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

    @Tool(name = "submit_garment_cutout", description = "当用户看完识别候选后表达确认/选择/加入衣橱意图时调用，提交选定的单品抠图任务。"
            + "识别候选推送后，用户说\"确认/就它了/确认加入/加入衣柜/帮我抠图\"等表达时，默认理解为用户确认选择该候选，"
            + "必须调用本工具提交抠图，不得只口头承诺\"正在抠图/马上发给你\"。"
            + "只可提交完整度 READY 的候选；任务在后台执行，完成后会主动发送草稿图、识别属性和最终确认提示。"
            + "候选要求重拍或用户只是询问（未表达确认/选择意图）时不可调用。"
            + "用户刚发送了新照片、或当前存在来自多张照片的候选时，必须传入 candidateIds 或 imageAssetId 明确目标，"
            + "严禁留空自动定位到其他照片的候选；只有确定全局仅有一张照片的一个待选候选时才允许留空。"
            + "注意区分：抠图完成后用户确认最终草稿才调用 confirm_wardrobe_candidate 入衣橱；"
            + "此刻尚无抠图草稿，用户\"确认加入\"对应的是提交抠图，而不是直接入衣橱。")
    public String submitGarmentCutout(
            @ToolParam(required = false, description = "用户明确选中的一个或多个完整候选 UUID；确定仅有一张照片的唯一待选候选时可为空。")
            List<String> candidateIds,
            @ToolParam(required = false, description = "用户当前要加入衣橱的那张照片的 img_ 编号；"
                    + "用户刚发送新照片时建议传入，工具会把候选限定到这张照片，避免误选其他照片的候选。")
            String imageAssetId
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            List<String> resolvedIds = resolveSelectionCandidateIds(userId, candidateIds, imageAssetId);
            List<GarmentCutoutTask> tasks = intake.selectCandidatesForCutout(userId, resolvedIds);
            String message = "已提交 " + tasks.size() + " 件衣物的草稿生成。完成后会自动把抠图和识别属性发到微信，"
                    + "用户确认后才会加入衣橱。";
            trace.toolResult("submit_garment_cutout", message);
            return message;
        } catch (IllegalStateException failure) {
            return failed("submit_garment_cutout", failure, "提交抠图失败：" + failure.getMessage());
        } catch (IllegalArgumentException failure) {
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

    @Tool(name = "retry_garment_cutout", description = "仅当用户明确要求基于原始上传照片重新提取、重新抠图或从原图重新生成时调用。"
            + "该工具始终只发送原始照片，不使用已有草稿；如果用户明确要求改变衣长、宽窄或颜色，可把该要求写入 instruction。"
            + "candidateId 是内部关联键；当前只有一件可重试衣物时可留空。")
    public String retryGarmentCutout(
            @ToolParam(required = false, description = "内部服装候选编号 UUID；当前只有一张待确认草稿时可为空。") String candidateId,
            @ToolParam(required = false, description = "基于原始照片重新生成时的明确要求，例如 保留完整裤脚、整体窄一点；为空表示忠实重新提取。") String instruction
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

    @Tool(name = "list_garment_draft_versions", description = "当用户询问衣物图片是否完成、还在不在处理，或想比较、查看、选择不同草稿版本时调用。"
            + "一个衣物草稿可以保留原版和多次修改后的多个可选版本；返回的版本序号可供后续预览、继续修改和确认。"
            + "它会读取 MySQL 中最近任务状态，不得根据聊天上下文猜测。candidateId 是内部关联键；仅有一件活动衣物时可为空。")
    public String listGarmentDraftVersions(
            @ToolParam(required = false, description = "内部服装候选编号 UUID；当前仅一件待确认草稿时可为空。") String candidateId
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            String resolvedCandidateId = resolveStatusCandidateId(userId, candidateId);
            List<GarmentDraftVersion> values = intake.draftVersions(userId, resolvedCandidateId);
            String status = intake.latestCutoutTask(userId, resolvedCandidateId)
                    .map(task -> describeTaskStatus(task, !values.isEmpty()))
                    .orElse("当前没有已提交的衣物图片任务。");
            String versionSummary = values.stream().map(this::describeVersion)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("这件衣物还没有生成可查看的草稿版本。");
            String message = status + "\n" + versionSummary
                    + "\n修改时可以说“基于原始照片重新做”或“基于第一版再改长一点”；来源不明确时必须先询问。";
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
            + "例如基于第一版、第二版或当前版调整衣长、裤长、宽窄、颜色或展示效果。它始终只发送一张选定草稿图，"
            + "不会覆盖或删除旧版本。candidateId 是内部关联键；仅有一件待确认草稿时可为空。"
            + "sourceVersionNumber 为空时以当前最新版为基础。用户没有说明基于原始照片还是草稿时，先调用 list_garment_draft_versions 并追问，不得猜测。")
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
            + "不可在抠图前或用户未确认时调用；若当前没有任何待确认草稿，不要调用本工具，"
            + "应直接引导用户先上传衣服照片。")
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
            return failed("confirm_wardrobe_candidate", failure, confirmFailureMessage(failure));
        } catch (RuntimeException failure) {
            return failed("confirm_wardrobe_candidate", failure, "确认入衣橱失败，请稍后重试。");
        }
    }

    /** 区分"没有任何待确认草稿"（应引导用户先上传衣服照片）与"有草稿但尚未就绪"两种情况。 */
    private static String confirmFailureMessage(RuntimeException failure) {
        if (failure instanceof IllegalStateException state) {
            if ("No completed wardrobe draft is awaiting confirmation".equals(state.getMessage())) {
                return "当前没有待确认的衣物草稿。若想把衣服加入衣橱，请先上传衣服照片，我会先帮你识别和抠图。";
            }
            if (state.getMessage() != null && state.getMessage().contains("多张照片")) {
                return "当前有多张照片的衣物草稿待确认，请先指明要确认哪张照片的哪件单品。";
            }
        }
        return "确认入衣橱失败：请先等待抠图完成，并确认候选编号正确。";
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
        if (sourcePhotoCount(values) > 1) {
            throw new IllegalStateException("当前有多张照片的衣物草稿待确认，请指明要确认哪张照片的哪件单品");
        }
        throw new IllegalStateException("More than one wardrobe draft is awaiting confirmation");
    }
    private String resolveStatusCandidateId(String userId, String candidateId) {
        if (!safe(candidateId).isBlank()) return safe(candidateId);
        List<ClothingCandidate> values = intake.activeWorkflowCandidates(userId);
        if (values.size() == 1) return values.getFirst().id();
        if (values.isEmpty()) throw new IllegalStateException("No active wardrobe candidate is available");
        throw new IllegalStateException("More than one active wardrobe candidate is available");
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
    private List<String> resolveSelectionCandidateIds(String userId, List<String> candidateIds, String imageAssetId) {
        List<String> requested = candidateIds == null ? List.of() : candidateIds.stream()
                .filter(value -> !safe(value).isBlank()).map(FashionWardrobeIntakeTools::safe).distinct().toList();
        if (!requested.isEmpty()) return requested;
        if (!safe(imageAssetId).isBlank()) {
            List<ClothingCandidate> photoCandidates = intake.candidatesForPhoto(userId, imageAssetId, null);
            List<ClothingCandidate> selectable = photoCandidates.stream()
                    .filter(value -> value.status() == ClothingCandidateStatus.PENDING_SELECTION)
                    .filter(value -> value.completenessStatus() == ClothingCompletenessStatus.READY)
                    .toList();
            if (selectable.size() == 1) return List.of(selectable.getFirst().id());
            if (selectable.isEmpty()) {
                throw new IllegalStateException(photoCandidates.isEmpty()
                        ? "这张照片还没有识别候选，请先调用 analyze_wardrobe_photo 识别后再提交抠图"
                        : "这张照片的候选尚未准备好提交抠图，请先等待识别完成");
            }
            throw new IllegalStateException("这张照片有多件待选候选，请明确选择要抠图的单品");
        }
        List<ClothingCandidate> values = intake.pendingSelectionCandidates(userId);
        if (values.size() == 1) return List.of(values.getFirst().id());
        if (values.isEmpty()) throw new IllegalStateException("当前没有可提交抠图的待选候选，请先上传并识别衣服照片");
        if (sourcePhotoCount(values) > 1) {
            throw new IllegalStateException("候选来自多张照片，请指明要处理哪张照片的哪件单品");
        }
        throw new IllegalStateException("当前有多件待选候选，请明确选择要抠图的单品");
    }
    /** 候选是否来自多张不同照片：跨照片自动定位会静默选错目标，必须改为显式指定。 */
    private static long sourcePhotoCount(List<ClothingCandidate> candidates) {
        return candidates.stream().map(ClothingCandidate::sourceAssetVersionId).distinct().count();
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
    private static String describeTaskStatus(GarmentCutoutTask task, boolean hasExistingDraft) {
        return switch (task.status()) {
            case PENDING -> "当前状态：任务已排队，尚未开始生成。";
            case PROCESSING -> "当前状态：图片正在后台生成。";
            case SUCCEEDED -> "当前状态：最近一次图片任务已经完成。";
            case FAILED -> hasExistingDraft
                    ? "当前状态：最近一次修改失败，但已有草稿仍然保留，可以继续查看、确认或重试。"
                    : "当前状态：最近一次生成失败，目前没有可确认草稿，可以基于原始照片重试。";
            case CANCELLED -> "当前状态：最近一次图片任务已取消。";
            case EXPIRED -> hasExistingDraft
                    ? "当前状态：最近一次任务已过期，但已有草稿仍然保留。"
                    : "当前状态：图片任务已过期，目前没有可确认草稿。";
        };
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
