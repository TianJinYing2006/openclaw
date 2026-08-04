package com.example.ykdsummer.ai.fashion.agent;

import com.example.ykdsummer.ai.fashion.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.model.CoordinatorOutput;
import com.example.ykdsummer.ai.fashion.model.CriticOutput;
import com.example.ykdsummer.ai.fashion.model.FashionRequest;
import com.example.ykdsummer.ai.fashion.model.StylistOutput;
import com.example.ykdsummer.ai.fashion.model.TrendOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FashionReviewAgentsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mockStylistOutputProvidesThreeBeachSuggestions() throws Exception {
        StylistOutput stylist = beachStylistOutput();

        assertThat(stylist.suggestions()).hasSize(3);
        assertThat(stylist.suggestions())
                .extracting(StylistOutput.OutfitSuggestion::id)
                .containsExactly(1, 2, 3);
        assertThat(stylist.suggestions().get(0).outfit().top()).contains("防晒衬衫");
        assertThat(stylist.suggestions().get(1).styleLabel()).contains("连衣裙");
        assertThat(stylist.suggestions().get(2).reasoning()).contains("容易吸热");
    }

    @Test
    void trendAgentCompletesMissingReviewsAndKeepsElementsNonEmpty() throws Exception {
        AgentLlmCaller llmCaller = mock(AgentLlmCaller.class);
        TrendOutput partialTrend = new TrendOutput(List.of(
                new TrendOutput.TrendReview(
                        2,
                        5,
                        "非常匹配",
                        List.of("亮色度假裙", "轻薄罩衫", "编织包"),
                        List.of(),
                        "亮色度假感和轻薄外搭符合海边拍照趋势。"
                )
        ));
        when(llmCaller.callAgent(
                eq(AgentPrompts.TREND),
                anyString(),
                eq(TrendOutput.class),
                eq(1500),
                eq(Duration.ofSeconds(60))
        )).thenReturn(partialTrend);

        TrendOutput output = new TrendAgent(llmCaller).execute(beachStylistOutput(), beachQuery());

        assertThat(output.trendAnalysis()).hasSize(3);
        assertThat(output.trendAnalysis())
                .extracting(TrendOutput.TrendReview::suggestionId)
                .containsExactly(1, 2, 3);
        assertThat(output.trendAnalysis().get(1).trendScore()).isEqualTo(5);
        assertThat(output.trendAnalysis().get(1).datedElements()).contains("无明显过时元素");
        assertThat(output.trendAnalysis())
                .allSatisfy(review -> {
                    assertThat(review.trendingElements()).isNotEmpty();
                    assertThat(review.datedElements()).isNotEmpty();
                });
    }

    @Test
    void coordinatorAgentKeepsSelectedIdValidAndCompletesRefinedOutfit() throws Exception {
        AgentLlmCaller llmCaller = mock(AgentLlmCaller.class);
        CoordinatorOutput invalidCoordinator = new CoordinatorOutput(
                new CoordinatorOutput.FinalRecommendation(
                        99,
                        "错误地选择了不存在的方案",
                        Map.of("rejected_2", "测试淘汰原因")
                ),
                new CoordinatorOutput.RefinedOutfit("", "", "", "", null),
                "",
                List.of("注意防晒")
        );
        when(llmCaller.callAgent(
                eq(AgentPrompts.COORDINATOR),
                anyString(),
                eq(CoordinatorOutput.class),
                eq(3000),
                eq(Duration.ofSeconds(60))
        )).thenReturn(invalidCoordinator);

        CoordinatorOutput output = new CoordinatorAgent(llmCaller).execute(
                new FashionRequest("test-user", "今天去海边穿什么"),
                beachStylistOutput(),
                CriticOutput.empty(),
                TrendOutput.neutralFor(beachStylistOutput()),
                "## RAG 知识参考\n海边穿搭要注意防晒、透气和行动便利。"
        );

        assertThat(output.finalRecommendation().selectedSuggestionId()).isEqualTo(1);
        assertThat(output.refinedOutfit().top()).contains("防晒衬衫");
        assertThat(output.refinedOutfit().bottom()).contains("高腰短裤");
        assertThat(output.refinedOutfit().shoes()).contains("凉鞋");
        assertThat(output.refinedOutfit().accessories()).contains("遮阳帽");
    }

    @Test
    void promptsContainTheRequiredTrendAndCoordinatorRules() {
        assertThat(AgentPrompts.TREND)
                .contains("不要全部给 3 分", "trendingElements 必须非空", "datedElements 必须非空");
        assertThat(AgentPrompts.COORDINATOR)
                .contains("体型适配 > 场合适配 > 风格偏好 > 趋势匹配", "不能自造编号", "refinedOutfit 必须完整");
    }

    private StylistOutput beachStylistOutput() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fashion/beach-stylist-output.json")) {
            assertThat(input).isNotNull();
            return objectMapper.readValue(input, StylistOutput.class);
        }
    }

    private AnalyzedQuery beachQuery() {
        return new AnalyzedQuery(
                "今天去海边穿什么",
                List.of("海边夏季穿搭", "度假防晒穿搭", "海边拍照穿搭"),
                new AnalyzedQuery.QueryParams("beach", "summer", 2, "unknown", "清爽度假")
        );
    }
}
