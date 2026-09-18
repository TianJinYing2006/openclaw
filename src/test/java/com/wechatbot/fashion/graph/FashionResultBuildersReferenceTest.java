package com.wechatbot.fashion.graph;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CoordinatorOutput;
import com.wechatbot.fashion.ai.fashion.look.model.CriticOutput;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import com.wechatbot.fashion.ai.fashion.look.model.TrendOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最终参考编号校验：必须落在本次检索上下文里，避免 LLM 复用上一套/幻觉编号导致「重新推荐却重复」。
 */
class FashionResultBuildersReferenceTest {

    private static final String RAG_CONTEXT = "1. [outfit_223] 海边参考\n\n2. [outfit_178] 通勤参考";

    private static FashionResult resultWithOutfitId(String outfitId) {
        return new FashionResult(
                true,
                new CoordinatorOutput(
                        new CoordinatorOutput.FinalRecommendation(1, "首选", Map.of()),
                        new CoordinatorOutput.RefinedOutfit("上装", "下装", "鞋", "配饰", outfitId),
                        "理由",
                        List.of()),
                StylistOutput.empty(),
                CriticOutput.empty(),
                TrendOutput.neutral(),
                RAG_CONTEXT,
                new AnalyzedQuery("q", List.of("q"), new AnalyzedQuery.QueryParams("DAILY", "SUMMER", 2, "u", "")),
                null,
                false);
    }

    @Test
    void extractsValidOutfitIdsFromRagContext() {
        assertThat(FashionResultBuilders.validOutfitIds(RAG_CONTEXT)).containsExactly("223", "178");
        assertThat(FashionResultBuilders.validOutfitIds("")).isEmpty();
        assertThat(FashionResultBuilders.validOutfitIds(null)).isEmpty();
    }

    @Test
    void replacesHallucinatedOrRepeatedIdWithContextId() {
        FashionResult fixed = FashionResultBuilders.withValidatedReferenceOutfit(
                resultWithOutfitId("153"), FashionResultBuilders.validOutfitIds(RAG_CONTEXT));

        assertThat(fixed.coordinator().refinedOutfit().referenceOutfitId()).isEqualTo("223");
    }

    @Test
    void keepsIdWhenItExistsInContext() {
        FashionResult same = FashionResultBuilders.withValidatedReferenceOutfit(
                resultWithOutfitId("178"), FashionResultBuilders.validOutfitIds(RAG_CONTEXT));

        assertThat(same.coordinator().refinedOutfit().referenceOutfitId()).isEqualTo("178");
    }

    @Test
    void noChangeWhenContextEmpty() {
        FashionResult same = FashionResultBuilders.withValidatedReferenceOutfit(
                resultWithOutfitId("153"), FashionResultBuilders.validOutfitIds(""));

        assertThat(same.coordinator().refinedOutfit().referenceOutfitId()).isEqualTo("153");
    }
}
