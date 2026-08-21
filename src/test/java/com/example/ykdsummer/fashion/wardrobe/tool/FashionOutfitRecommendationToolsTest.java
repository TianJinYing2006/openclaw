package com.example.ykdsummer.fashion.wardrobe.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.wardrobe.application.OutfitRecommendationService;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRecommendationRequest;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRecommendationResult;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRenderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class FashionOutfitRecommendationToolsTest {

    @Test
    void injectsCurrentUserAndReturnsNaturalRankedOptionsWithoutInternalIds() {
        OutfitRecommendationService service = mock(OutfitRecommendationService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionOutfitRecommendationTools tools = new FashionOutfitRecommendationTools(
                service, collector, AiTraceLogger.disabled());
        when(service.recommend(any())).thenReturn(result());

        String output = callback(tools).call("""
                {"wardrobeItemId":42,"occasionTags":["通勤"],"seasonTags":["夏季"],
                 "weatherSummary":"武汉 30°C 晴","styleTags":["简约"],"targetTime":"明天上午","limit":3}
                """);

        assertThat(output)
                .contains("第 1 套", "白色短袖 + 深蓝直筒裤", "后台处理")
                .doesNotContain("internal-option", "wardrobeItemId", "42", "89.4", "reference-1");
        ArgumentCaptor<OutfitRecommendationRequest> request =
                ArgumentCaptor.forClass(OutfitRecommendationRequest.class);
        verify(service).recommend(request.capture());
        assertThat(request.getValue().externalUserId()).isEqualTo("managed:instance-a:wechat-user");
        assertThat(request.getValue().anchorWardrobeItemId()).isEqualTo(42L);
        assertThat(request.getValue().occasionTags()).containsExactly("通勤");
        assertThat(request.getValue().maxResults()).isEqualTo(3);
        collector.finish();
    }

    @Test
    void explainsEvidenceBoundMissingItemWithoutInventingAnOutfit() {
        OutfitRecommendationService service = mock(OutfitRecommendationService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("wechat-user");
        FashionOutfitRecommendationTools tools = new FashionOutfitRecommendationTools(
                service, collector, AiTraceLogger.disabled());
        when(service.recommend(any())).thenReturn(new OutfitRecommendationResult(
                "run-internal", 42L, List.of(),
                new OutfitRecommendationResult.MissingItem("BOTTOM",
                        "衣橱里暂时缺少适合这件衣服的深色直筒裤", 4, 0.8d), Instant.now()));

        String output = callback(tools).call("{\"wardrobeItemId\":42}");

        assertThat(output).contains("深色直筒裤", "搭配证据")
                .doesNotContain("run-internal", "BOTTOM", "42");
        collector.finish();
    }

    private static OutfitRecommendationResult result() {
        OutfitRecommendationResult.Item top = new OutfitRecommendationResult.Item(
                42L, "TOP", "白色短袖", "T_SHIRT", "WHITE", 101L, "img_top", 1);
        OutfitRecommendationResult.Item bottom = new OutfitRecommendationResult.Item(
                43L, "BOTTOM", "深蓝直筒裤", "STRAIGHT_PANTS", "NAVY", 102L, "img_bottom", 1);
        OutfitRecommendationResult.Evidence evidence = new OutfitRecommendationResult.Evidence(
                71L, "reference-1", "STRAIGHT_PANTS", "深蓝直筒裤", 0.82d);
        OutfitRecommendationResult.Option option = new OutfitRecommendationResult.Option(
                "internal-option", 1, List.of(top, bottom), 89.4d,
                Map.of("evidence", 0.91d, "color", 0.92d, "occasion", 0.85d),
                List.of(evidence), "白色短袖 + 深蓝直筒裤",
                OutfitRenderStatus.SUBMITTED, "", 0, "");
        return new OutfitRecommendationResult("run-internal", 42L, List.of(option), null, Instant.now());
    }

    private static ToolCallback callback(Object tools) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name()
                        .equals("recommend_outfits_from_wardrobe"))
                .findFirst().orElseThrow();
    }
}
