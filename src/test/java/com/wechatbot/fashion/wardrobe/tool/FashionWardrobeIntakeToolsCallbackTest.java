package com.wechatbot.fashion.wardrobe.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechatbot.fashion.ai.service.AiTraceLogger;
import com.wechatbot.fashion.ai.tool.ToolArtifactCollector;
import com.wechatbot.fashion.wardrobe.application.FashionWardrobeIngestionService;
import com.wechatbot.fashion.wardrobe.domain.ClothingCandidate;
import com.wechatbot.fashion.wardrobe.domain.ClothingCandidateStatus;
import com.wechatbot.fashion.wardrobe.domain.ClothingCompletenessStatus;
import com.wechatbot.fashion.wardrobe.domain.FashionImageAsset;
import com.wechatbot.fashion.wardrobe.domain.GarmentDraftVersion;
import com.wechatbot.fashion.wardrobe.domain.GarmentCutoutTask;
import com.wechatbot.fashion.wardrobe.domain.GarmentCutoutTaskStatus;
import com.wechatbot.fashion.wardrobe.domain.WardrobeItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class FashionWardrobeIntakeToolsCallbackTest {

    @Test
    void analyzesCurrentUsersPhotoAndOnlyConfirmsAfterTheWorkflowIsReady() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        String userId = "managed:11111111-1111-1111-1111-111111111111:wechat-user";
        collector.begin(userId);
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        ClothingCandidate candidate = candidate("candidate-1", ClothingCandidateStatus.PENDING_SELECTION,
                ClothingCompletenessStatus.READY, null);
        when(service.candidatesForPhoto(eq(userId), eq("img_shirt"), eq(1)))
                .thenReturn(List.of(candidate));
        ClothingCandidate ready = candidate("candidate-ready", ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION,
                ClothingCompletenessStatus.READY, 12L);
        when(service.candidate(eq(userId), eq("candidate-ready"))).thenReturn(Optional.of(ready));
        when(service.draftReviewSummary(ready)).thenReturn("- 类型：短袖上衣\n- 颜色：白色");
        when(service.confirmCandidate(eq(userId), eq("candidate-ready")))
                .thenReturn(item(23L));

        String analysis = callback(tools, "analyze_wardrobe_photo").call("{\"imageAssetId\":\"img_shirt\",\"imageVersion\":1}");
        String confirmed = callback(tools, "confirm_wardrobe_candidate").call("{\"candidateId\":\"candidate-ready\"}");

        assertThat(analysis).contains("内部候选", "candidate-1", "可提交抠图", "用户只需用名称");
        assertThat(confirmed).contains("已加入个人衣橱", "短袖上衣").doesNotContain("#23");
        verify(service).candidatesForPhoto(userId, "img_shirt", 1);
        verify(service).confirmCandidate(userId, "candidate-ready");
        collector.finish();
    }

    @Test
    void reportsThatPhotoAnalysisIsRunningInTheBackgroundWhenNoCandidateExistsYet() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = collectorFor("wechat-user");
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        when(service.candidatesForPhoto(eq("wechat-user"), eq("img_shirt"), isNull()))
                .thenReturn(List.of());
        when(service.submitPhotoAnalysis(eq("wechat-user"), eq("img_shirt"), isNull()))
                .thenReturn(true);

        String analysis = callback(tools, "analyze_wardrobe_photo").call("{\"imageAssetId\":\"img_shirt\"}");

        assertThat(analysis).contains("正在识别图片中");
        verify(service).submitPhotoAnalysis("wechat-user", "img_shirt", null);
        collector.finish();
    }

    @Test
    void confirmsTheOnlyCompletedDraftWithoutExposingOrRequiringItsIdentifier() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        String userId = "managed:11111111-1111-1111-1111-111111111111:wechat-user";
        collector.begin(userId);
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        ClothingCandidate draft = candidate("internal-candidate", ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION,
                ClothingCompletenessStatus.READY, 12L);
        when(service.activeWorkflowCandidates(userId)).thenReturn(List.of(draft));
        when(service.awaitingFinalConfirmationCandidates(userId)).thenReturn(List.of(draft));
        when(service.candidate(userId, "internal-candidate")).thenReturn(Optional.of(draft));
        when(service.confirmCandidate(userId, "internal-candidate")).thenReturn(item(24L));
        when(service.draftReviewSummary(draft)).thenReturn("- 类型：短袖上衣\n- 颜色：白色");

        String confirmed = callback(tools, "confirm_wardrobe_candidate").call("{}");

        assertThat(confirmed).contains("已加入个人衣橱", "短袖上衣").doesNotContain("internal-candidate", "#24");
        verify(service).confirmCandidate(userId, "internal-candidate");
        collector.finish();
    }

    @Test
    void refusesToMutateWardrobeWithoutTheCurrentWechatIdentity() {
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(
                mock(FashionWardrobeIngestionService.class), new ToolArtifactCollector(), AiTraceLogger.disabled());

        assertThat(callback(tools, "confirm_wardrobe_candidate").call("{\"candidateId\":\"candidate-1\"}"))
                .contains("当前会话身份不可用");
    }

    @Test
    void listsAndConfirmsARequestedDraftVersionWithoutLeakingInternalIdentifiers() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        String userId = "managed:11111111-1111-1111-1111-111111111111:wechat-user";
        collector.begin(userId);
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        ClothingCandidate draft = candidate("internal-candidate", ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION,
                ClothingCompletenessStatus.READY, 22L);
        List<GarmentDraftVersion> versions = List.of(
                new GarmentDraftVersion(1, "task-one", "", new FashionImageAsset(12L, "img_one", 1, "image/png"),
                        Instant.now(), false),
                new GarmentDraftVersion(2, "task-two", "衣长加长一点", new FashionImageAsset(22L, "img_two", 1, "image/png"),
                        Instant.now(), true));
        when(service.activeWorkflowCandidates(userId)).thenReturn(List.of(draft));
        when(service.awaitingFinalConfirmationCandidates(userId)).thenReturn(List.of(draft));
        when(service.candidate(userId, "internal-candidate")).thenReturn(Optional.of(draft));
        when(service.draftVersions(userId, "internal-candidate")).thenReturn(versions);
        when(service.draftReviewSummary(draft)).thenReturn("- 类型：短袖上衣");
        when(service.confirmCandidate(userId, "internal-candidate", 1)).thenReturn(item(25L));

        String listed = callback(tools, "list_garment_draft_versions").call("{}");
        String confirmed = callback(tools, "confirm_wardrobe_candidate").call("{\"versionNumber\":1}");

        assertThat(listed).contains("第1版", "第2版", "当前最新版").doesNotContain("task-one", "task-two", "img_one", "img_two");
        assertThat(confirmed).contains("已加入个人衣橱").doesNotContain("internal-candidate", "#25");
        verify(service).confirmCandidate(userId, "internal-candidate", 1);
        collector.finish();
    }

    @Test
    void reportsDurableProcessingStatusWithoutExposingTaskIdentifiers() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = collectorFor("wechat-user");
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        ClothingCandidate candidate = candidate("processing-one", ClothingCandidateStatus.CUTOUT_SUBMITTED,
                ClothingCompletenessStatus.READY, null);
        GarmentCutoutTask processing = task("private-task", "processing-one", GarmentCutoutTaskStatus.PROCESSING);
        when(service.activeWorkflowCandidates("wechat-user")).thenReturn(List.of(candidate));
        when(service.draftVersions("wechat-user", "processing-one")).thenReturn(List.of());
        when(service.latestCutoutTask("wechat-user", "processing-one")).thenReturn(Optional.of(processing));

        String result = callback(tools, "list_garment_draft_versions").call("{}");

        assertThat(result).contains("正在后台生成", "还没有生成可查看的草稿").doesNotContain("private-task", "processing-one");
        collector.finish();
    }

    @Test
    void submitsTheOnlyPendingCandidateWhenTheModelOmitsItsInternalIdentifier() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = collectorFor("wechat-user");
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        ClothingCandidate pending = candidate("pending-one", ClothingCandidateStatus.PENDING_SELECTION,
                ClothingCompletenessStatus.READY, null);
        when(service.pendingSelectionCandidates("wechat-user")).thenReturn(List.of(pending));
        when(service.selectCandidatesForCutout("wechat-user", List.of("pending-one")))
                .thenReturn(List.of(task("task-one", "pending-one")));

        String result = callback(tools, "submit_garment_cutout").call("{}");

        assertThat(result).contains("已提交 1 件衣物").doesNotContain("pending-one", "task-one");
        verify(service).selectCandidatesForCutout("wechat-user", List.of("pending-one"));
        collector.finish();
    }

    @Test
    void refusesToGuessWhenMoreThanOnePendingCandidateExists() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = collectorFor("wechat-user");
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        when(service.pendingSelectionCandidates("wechat-user")).thenReturn(List.of(
                candidate("top", ClothingCandidateStatus.PENDING_SELECTION, ClothingCompletenessStatus.READY, null),
                candidate("pants", ClothingCandidateStatus.PENDING_SELECTION, ClothingCompletenessStatus.READY, null)));

        String result = callback(tools, "submit_garment_cutout").call("{}");

        assertThat(result).contains("提交抠图失败");
        verify(service, never()).selectCandidatesForCutout(any(), anyList());
        collector.finish();
    }

    @Test
    void submitsOnlyCandidatesOfTheRequestedPhotoWhenAnotherPhotoIsAlsoPending() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = collectorFor("wechat-user");
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        when(service.candidatesForPhoto(eq("wechat-user"), eq("img_new"), isNull())).thenReturn(List.of(
                candidate("new-pending", 21L, ClothingCandidateStatus.PENDING_SELECTION,
                        ClothingCompletenessStatus.READY, null)));
        when(service.selectCandidatesForCutout("wechat-user", List.of("new-pending")))
                .thenReturn(List.of(task("task-new", "new-pending")));

        String result = callback(tools, "submit_garment_cutout").call("{\"imageAssetId\":\"img_new\"}");

        assertThat(result).contains("已提交 1 件衣物").doesNotContain("new-pending", "task-new");
        verify(service).selectCandidatesForCutout("wechat-user", List.of("new-pending"));
        collector.finish();
    }

    @Test
    void refusesToAutoResolveAcrossPhotosWithoutAnExplicitTarget() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = collectorFor("wechat-user");
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        when(service.pendingSelectionCandidates("wechat-user")).thenReturn(List.of(
                candidate("old-pending", 11L, ClothingCandidateStatus.PENDING_SELECTION,
                        ClothingCompletenessStatus.READY, null),
                candidate("new-pending", 21L, ClothingCandidateStatus.PENDING_SELECTION,
                        ClothingCompletenessStatus.READY, null)));

        String result = callback(tools, "submit_garment_cutout").call("{}");

        assertThat(result).contains("提交抠图失败").contains("多张照片");
        verify(service, never()).selectCandidatesForCutout(any(), anyList());
        collector.finish();
    }

    @Test
    void guidesTheModelToAnalyzeANewPhotoFirstWhenSubmitTargetsAnUnrecognizedPhoto() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = collectorFor("wechat-user");
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        when(service.candidatesForPhoto(eq("wechat-user"), eq("img_new"), isNull())).thenReturn(List.of());

        String result = callback(tools, "submit_garment_cutout").call("{\"imageAssetId\":\"img_new\"}");

        assertThat(result).contains("提交抠图失败").contains("analyze_wardrobe_photo");
        verify(service, never()).selectCandidatesForCutout(any(), anyList());
        collector.finish();
    }

    @Test
    void updatesTheOnlyPreCutoutCandidateAndCancelsTheOnlyActiveCandidate() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = collectorFor("wechat-user");
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        ClothingCandidate pending = candidate("pending-one", ClothingCandidateStatus.PENDING_SELECTION,
                ClothingCompletenessStatus.READY, null);
        when(service.pendingSelectionCandidates("wechat-user")).thenReturn(List.of(pending));
        when(service.awaitingFinalConfirmationCandidates("wechat-user")).thenReturn(List.of());
        when(service.candidate("wechat-user", "pending-one")).thenReturn(Optional.of(pending));
        when(service.updateCandidateLabels(eq("wechat-user"), eq("pending-one"), any()))
                .thenReturn(pending);
        when(service.draftReviewSummary(pending)).thenReturn("- 类型：短袖上衣\n- 颜色：灰色");
        when(service.activeWorkflowCandidates("wechat-user")).thenReturn(List.of(pending));
        when(service.cancelCandidates("wechat-user", List.of("pending-one"))).thenReturn(1);

        String updated = callback(tools, "update_wardrobe_candidate_labels")
                .call("{\"displayName\":\"灰色印花短袖\"}");
        String cancelled = callback(tools, "cancel_wardrobe_candidate").call("{}");

        assertThat(updated).contains("草稿标签已更新", "灰色");
        assertThat(cancelled).contains("已取消 1 件");
        verify(service).updateCandidateLabels(eq("wechat-user"), eq("pending-one"), any());
        verify(service).cancelCandidates("wechat-user", List.of("pending-one"));
        collector.finish();
    }

    @Test
    void retriesTheOnlyFailedCandidateWithoutRequiringTheModelToRememberItsIdentifier() {
        FashionWardrobeIngestionService service = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = collectorFor("wechat-user");
        FashionWardrobeIntakeTools tools = new FashionWardrobeIntakeTools(service, collector, AiTraceLogger.disabled());
        ClothingCandidate failed = candidate("failed-one", ClothingCandidateStatus.FAILED,
                ClothingCompletenessStatus.READY, null);
        when(service.activeWorkflowCandidates("wechat-user")).thenReturn(List.of(failed));
        when(service.retryCutout("wechat-user", "failed-one", "保留完整印花"))
                .thenReturn(task("retry-one", "failed-one"));

        String result = callback(tools, "retry_garment_cutout")
                .call("{\"instruction\":\"保留完整印花\"}");

        assertThat(result).contains("已重新提交").doesNotContain("failed-one", "retry-one");
        verify(service).retryCutout("wechat-user", "failed-one", "保留完整印花");
        collector.finish();
    }

    private static ToolArtifactCollector collectorFor(String userId) {
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin(userId);
        return collector;
    }

    private static GarmentCutoutTask task(String id, String candidateId) {
        return task(id, candidateId, GarmentCutoutTaskStatus.PENDING);
    }

    private static GarmentCutoutTask task(String id, String candidateId, GarmentCutoutTaskStatus status) {
        Instant now = Instant.now();
        return new GarmentCutoutTask(id, candidateId, 7L, "instance", 11L, 1, "",
                status, null, "", null, null, now.plusSeconds(600), now, now);
    }

    private static ClothingCandidate candidate(String id, ClothingCandidateStatus status,
                                                ClothingCompletenessStatus completeness, Long cutoutAssetVersionId) {
        return candidate(id, 11L, status, completeness, cutoutAssetVersionId);
    }

    private static ClothingCandidate candidate(String id, long sourceAssetVersionId, ClothingCandidateStatus status,
                                                ClothingCompletenessStatus completeness, Long cutoutAssetVersionId) {
        Instant now = Instant.now();
        return new ClothingCandidate(id, 7L, "instance", sourceAssetVersionId, 0, "白色短袖", "T_SHIRT", "WHITE",
                List.of(), List.of("MINIMAL"), "RELAXED", List.of("SUMMER"), "{}", new BigDecimal("0.92"),
                new BigDecimal("0.90"), completeness, "", status, cutoutAssetVersionId, null,
                "test", "test", "fashion-v1", now.plusSeconds(600), now, now);
    }

    private static WardrobeItem item(long id) {
        Instant now = Instant.now();
        return new WardrobeItem(id, 7L, "instance", "T_SHIRT", "WHITE", List.of(), List.of("MINIMAL"),
                "RELAXED", "SOLID", List.of("SUMMER"), List.of(), "COTTON", "ACTIVE", "PENDING", 0,
                null, "USER_CONFIRMED", "", now, now);
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
