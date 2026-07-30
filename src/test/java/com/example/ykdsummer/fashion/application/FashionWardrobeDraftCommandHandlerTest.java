package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.domain.ClothingCandidateStatus;
import com.example.ykdsummer.fashion.domain.ClothingCompletenessStatus;
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
}
