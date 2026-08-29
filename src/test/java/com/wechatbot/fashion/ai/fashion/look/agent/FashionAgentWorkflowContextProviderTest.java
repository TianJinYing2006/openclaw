package com.wechatbot.fashion.ai.fashion.look.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wechatbot.fashion.wardrobe.application.FashionWardrobeIngestionService;
import com.wechatbot.fashion.wardrobe.application.OutfitRecommendationService;
import com.wechatbot.fashion.wardrobe.domain.ClothingCandidate;
import com.wechatbot.fashion.wardrobe.domain.ClothingCandidateStatus;
import com.wechatbot.fashion.wardrobe.domain.ClothingCompletenessStatus;
import com.wechatbot.fashion.wardrobe.domain.OutfitRecommendationResult;
import com.wechatbot.fashion.wardrobe.domain.OutfitRenderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                .contains("结合紧邻历史中的未执行修改要求")
                .contains("严禁向用户展示 candidateId");
        when(ingestion.activeWorkflowCandidates("empty-user")).thenReturn(List.of());
        assertThat(provider.contextFor("empty-user")).isEmpty();
    }

    @Test
    void rehydratesRankedOutfitSelectionAfterConversationMemoryIsGone() {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        OutfitRecommendationService recommendations = mock(OutfitRecommendationService.class);
        FashionAgentWorkflowContextProvider provider = new FashionAgentWorkflowContextProvider(ingestion);
        provider.setOutfitRecommendations(recommendations);
        when(ingestion.activeWorkflowCandidates("wechat-user")).thenReturn(List.of());
        OutfitRecommendationResult.Item top = new OutfitRecommendationResult.Item(
                11L, "TOP", "白色短袖", "T_SHIRT", "WHITE", 101L, "img_top", 1);
        OutfitRecommendationResult.Item bottom = new OutfitRecommendationResult.Item(
                12L, "BOTTOM", "深蓝直筒裤", "STRAIGHT_PANTS", "NAVY", 102L, "img_bottom", 1);
        OutfitRecommendationResult.Option option = new OutfitRecommendationResult.Option(
                "internal-option", 2, List.of(top, bottom), 86d, Map.of("evidence", 0.9d),
                List.of(), "白色短袖 + 深蓝直筒裤", OutfitRenderStatus.FALLBACK,
                "img_result", 1, "");
        when(recommendations.latest("wechat-user")).thenReturn(Optional.of(
                new OutfitRecommendationResult("internal-run", 11L, List.of(option), null, Instant.now())));

        String context = provider.contextFor("wechat-user");

        assertThat(context)
                .contains("rank=2")
                .contains("optionId=internal-option")
                .contains("TOP:wardrobeItemId=11:白色短袖")
                .contains("FALLBACK")
                .contains("严格按 rank 定位")
                .contains("严禁展示内部 ID");
    }

    private static ClothingCandidate candidate(String id, ClothingCandidateStatus status) {
        Instant now = Instant.now();
        return new ClothingCandidate(id, 7L, "instance", 11L, 0, "灰色印花短袖", "T_SHIRT", "GRAY",
                List.of("BLACK"), List.of("CASUAL"), "RELAXED", List.of("SUMMER"), "{}",
                new BigDecimal("0.92"), new BigDecimal("0.90"), ClothingCompletenessStatus.READY, "", status,
                null, null, "test", "test", "fashion-v1", now.plusSeconds(600), now, now);
    }
}
