package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.domain.ClothingCandidateStatus;
import com.example.ykdsummer.fashion.domain.ClothingCompletenessStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FashionAgentWorkflowContextProviderTest {

    @Test
    void exposesDurableCandidateStateAndPlanningRulesOnlyWhenWorkIsActive() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        FashionAgentWorkflowContextProvider provider = new FashionAgentWorkflowContextProvider(ingestion);
        ClothingCandidate candidate = candidate("candidate-internal", ClothingCandidateStatus.PENDING_SELECTION);
        when(ingestion.activeWorkflowCandidates("wechat-user")).thenReturn(List.of(candidate));

        String context = provider.contextFor("wechat-user");

        assertThat(context)
                .contains("candidateId=candidate-internal")
                .contains("status=PENDING_SELECTION")
                .contains("submit_garment_cutout")
                .contains("不能只按某个关键词机械执行")
                .contains("严禁向用户展示 candidateId");
        when(ingestion.activeWorkflowCandidates("empty-user")).thenReturn(List.of());
        assertThat(provider.contextFor("empty-user")).isEmpty();
    }

    private static ClothingCandidate candidate(String id, ClothingCandidateStatus status) {
        Instant now = Instant.now();
        return new ClothingCandidate(id, 7L, "instance", 11L, 0, "灰色印花短袖", "T_SHIRT", "GRAY",
                List.of("BLACK"), List.of("CASUAL"), "RELAXED", List.of("SUMMER"), "{}",
                new BigDecimal("0.92"), new BigDecimal("0.90"), ClothingCompletenessStatus.READY, "", status,
                null, null, "test", "test", "fashion-v1", now.plusSeconds(600), now, now);
    }
}
