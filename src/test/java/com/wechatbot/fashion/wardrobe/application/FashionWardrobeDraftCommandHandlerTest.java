package com.wechatbot.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechatbot.fashion.wardrobe.domain.ClothingCandidate;
import com.wechatbot.fashion.wardrobe.domain.ClothingCandidateStatus;
import com.wechatbot.fashion.wardrobe.domain.ClothingCompletenessStatus;
import com.wechatbot.fashion.wardrobe.domain.FashionImageAsset;
import com.wechatbot.fashion.wardrobe.domain.GarmentDraftVersion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FashionWardrobeDraftCommandHandlerTest {

    @Test
    void submitsDurableRevisionForAnExplicitLengthChangeToTheOnlyPendingDraft() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        when(ingestion.awaitingFinalConfirmationCandidates("wechat-user")).thenReturn(List.of(candidate("candidate-1")));
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        String reply = handler.handle("wechat-user", "把这一版加长一点吧").orElseThrow();

        assertThat(reply).contains("已提交", "自动");
        verify(ingestion).reviseDraft(eq("wechat-user"), eq("candidate-1"), isNull(), eq("把这一版加长一点吧"));
    }

    @Test
    void routesExplicitOriginalPhotoLanguageToRecut() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        when(ingestion.awaitingFinalConfirmationCandidates("wechat-user")).thenReturn(List.of(candidate("candidate-1")));
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        String reply = handler.handle("wechat-user", "基于原图重新提取，整体变窄一点").orElseThrow();

        assertThat(reply).contains("原始上传照片", "重新生成");
        verify(ingestion).retryCutout("wechat-user", "candidate-1", "基于原图重新提取，整体变窄一点");
        verify(ingestion, never()).reviseDraft(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void routesPreviousDraftLanguageToTheVersionBeforeCurrent() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        when(ingestion.awaitingFinalConfirmationCandidates("wechat-user")).thenReturn(List.of(candidate("candidate-1")));
        when(ingestion.draftVersions("wechat-user", "candidate-1"))
                .thenReturn(List.of(version(1, false), version(2, true)));
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        String reply = handler.handle("wechat-user", "基于上一版改短一点").orElseThrow();

        assertThat(reply).contains("第1版草稿", "已提交");
        verify(ingestion).reviseDraft("wechat-user", "candidate-1", 1, "基于上一版改短一点");
    }

    @Test
    void routesNumberedDraftLanguageToTheRequestedSingleVersion() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        when(ingestion.awaitingFinalConfirmationCandidates("wechat-user")).thenReturn(List.of(candidate("candidate-1")));
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        String reply = handler.handle("wechat-user", "基于第一版改短一点").orElseThrow();

        assertThat(reply).contains("已提交", "第1版草稿");
        verify(ingestion).reviseDraft("wechat-user", "candidate-1", 1, "基于第一版改短一点");
    }

    @Test
    void asksForTheSourceWhenOriginalVersionLanguageIsAmbiguous() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        when(ingestion.awaitingFinalConfirmationCandidates("wechat-user")).thenReturn(List.of(candidate("candidate-1")));
        when(ingestion.draftVersions("wechat-user", "candidate-1")).thenReturn(List.of(version(1, true)));
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        String reply = handler.handle("wechat-user", "第一版太差了，基于原版改瘦一点").orElseThrow();

        assertThat(reply).contains("原始上传照片", "当前", "第一版");
        verify(ingestion, never()).retryCutout(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(ingestion, never()).reviseDraft(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void asksForTheSourceWhenAVisualEditHasNoReferenceCue() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        when(ingestion.awaitingFinalConfirmationCandidates("wechat-user")).thenReturn(List.of(candidate("candidate-1")));
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        String reply = handler.handle("wechat-user", "衣服变瘦变短一点").orElseThrow();

        assertThat(reply).contains("原始上传照片", "继续修改");
        verify(ingestion, never()).retryCutout(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(ingestion, never()).reviseDraft(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void leavesAmbiguousOrUnrelatedMessagesToTheAgent() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        when(ingestion.awaitingFinalConfirmationCandidates("wechat-user"))
                .thenReturn(List.of(candidate("candidate-1"), candidate("candidate-2")));
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        assertThat(handler.handle("wechat-user", "把这一版加长一点吧")).isEmpty();
        assertThat(handler.handle("wechat-user", "今天武汉天气怎么样")).isEmpty();
        verify(ingestion, never()).reviseDraft(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void leavesGarmentAttributeQuestionsToTheAgentInsteadOfTreatingThemAsEdits() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        assertThat(handler.handleExplicitRevision("wechat-user", "这件衣服是什么版型？")).isEmpty();
        assertThat(handler.handleExplicitRevision("wechat-user", "这条裤子的裤长是多少？")).isEmpty();
        verify(ingestion, never()).awaitingFinalConfirmationCandidates(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void submitsTheOnlyPendingCandidateWhenUserConfirmsWithShortAffirmativeReply() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        when(ingestion.awaitingFinalConfirmationCandidates("wechat-user")).thenReturn(List.of());
        when(ingestion.pendingSelectionCandidates("wechat-user")).thenReturn(List.of(candidate(
                "candidate-1", ClothingCandidateStatus.PENDING_SELECTION, null)));
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        String reply = handler.handle("wechat-user", "OK！").orElseThrow();

        assertThat(reply).contains("已提交", "抠图");
        verify(ingestion).selectCandidatesForCutout("wechat-user", List.of("candidate-1"));
    }

    @Test
    void doesNotTreatNegativeReplyAsConfirmation() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        when(ingestion.awaitingFinalConfirmationCandidates("wechat-user")).thenReturn(List.of());
        when(ingestion.pendingSelectionCandidates("wechat-user")).thenReturn(List.of(candidate(
                "candidate-1", ClothingCandidateStatus.PENDING_SELECTION, null)));
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        assertThat(handler.handle("wechat-user", "先不要")).isEmpty();
        verify(ingestion, never()).selectCandidatesForCutout(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmsTheOnlyCompletedCandidateWhenUserUsesShortAffirmativeReply() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        when(ingestion.awaitingFinalConfirmationCandidates("wechat-user")).thenReturn(List.of(candidate("candidate-1")));
        when(ingestion.pendingSelectionCandidates("wechat-user")).thenReturn(List.of());
        FashionWardrobeDraftCommandHandler handler = new FashionWardrobeDraftCommandHandler(ingestion);

        String reply = handler.handle("wechat-user", "确认").orElseThrow();

        assertThat(reply).contains("已加入", "衣橱");
        verify(ingestion).confirmCandidate("wechat-user", "candidate-1");
    }

    private static ClothingCandidate candidate(String id) {
        return candidate(id, ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION, 3L);
    }

    private static ClothingCandidate candidate(String id, ClothingCandidateStatus status, Long currentCutoutAssetVersionId) {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new ClothingCandidate(id, 1L, "instance", 2L, 0, "灰色上衣", "T_SHIRT", "GRAY",
                List.of(), List.of("CASUAL"), "REGULAR", List.of("SUMMER"), "{}", BigDecimal.ONE, BigDecimal.ONE,
                ClothingCompletenessStatus.READY, "", status, currentCutoutAssetVersionId, null,
                "test", "test", "v1", now.plusSeconds(1800), now, now);
    }

    private static GarmentDraftVersion version(int number, boolean current) {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new GarmentDraftVersion(number, "task-" + number, "", new FashionImageAsset(
                (long) number, "img-" + number, 1, "image/png"), now, current);
    }
}
