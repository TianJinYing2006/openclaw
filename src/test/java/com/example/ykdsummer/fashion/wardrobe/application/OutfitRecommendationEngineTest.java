package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.fashion.wardrobe.config.OutfitRecommendationProperties;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionReferenceGarment;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRecommendationRequest;
import com.example.ykdsummer.fashion.wardrobe.domain.SemanticReferenceGarmentMatch;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutfitRecommendationEngineTest {
    private final OutfitRecommendationEngine engine =
            new OutfitRecommendationEngine(new OutfitRecommendationProperties());

    @Test
    void ranksTheUsersClosestCompanionFromPublicLookEvidence() {
        WardrobeItem anchor = wardrobe(1, "浅灰宽松T恤", "TOP", "T_SHIRT", "LIGHT_GRAY",
                List.of("MINIMAL", "CASUAL"), "RELAXED", "SOLID", List.of("SUMMER"), List.of("DAILY"), "COTTON");
        WardrobeItem matchingBottom = wardrobe(2, "深蓝直筒牛仔裤", "BOTTOM", "JEANS", "DENIM_BLUE",
                List.of("CASUAL"), "STRAIGHT", "SOLID", List.of("ALL_SEASON"), List.of("DAILY"), "DENIM");
        WardrobeItem weakerBottom = wardrobe(3, "红色运动裤", "BOTTOM", "STRAIGHT_PANTS", "RED",
                List.of("SPORT"), "RELAXED", "SOLID", List.of("SUMMER"), List.of("SPORT"), "COTTON");
        SemanticReferenceGarmentMatch publicMatch = publicMatch(8L, 0.91d);
        OutfitRecommendationRequest request = request(anchor.id(), 3);

        OutfitRecommendationEngine.EngineResult result = engine.recommend(anchor,
                List.of(anchor, weakerBottom, matchingBottom), List.of(publicMatch), List.of(), Set.of(), request);

        assertThat(result.missingItem()).isNull();
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates().getFirst().items().get("BOTTOM").id()).isEqualTo(2L);
        assertThat(result.candidates().getFirst().breakdown()).containsKeys(
                "evidence", "occasion", "seasonWeather", "color", "style");
        assertThat(result.candidates().getFirst().evidence()).singleElement()
                .satisfies(value -> assertThat(value.referenceLookId()).isEqualTo(8L));
    }

    @Test
    void reportsAConcreteMissingBottomInsteadOfInventingAnOutfit() {
        WardrobeItem anchor = wardrobe(1, "浅灰宽松T恤", "TOP", "T_SHIRT", "LIGHT_GRAY",
                List.of("MINIMAL", "CASUAL"), "RELAXED", "SOLID", List.of("SUMMER"), List.of("DAILY"), "COTTON");

        OutfitRecommendationEngine.EngineResult result = engine.recommend(anchor, List.of(anchor),
                List.of(publicMatch(8L, 0.88d)), List.of(), Set.of(), request(anchor.id(), 3));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.missingItem()).isNotNull();
        assertThat(result.missingItem().categoryCode()).isEqualTo("BOTTOM");
        assertThat(result.missingItem().summary()).contains("牛仔蓝", "牛仔裤");
    }

    @Test
    void mergesDuplicateCombinationsSupportedByMultipleLooks() {
        WardrobeItem anchor = wardrobe(1, "浅灰宽松T恤", "TOP", "T_SHIRT", "LIGHT_GRAY",
                List.of("MINIMAL", "CASUAL"), "RELAXED", "SOLID", List.of("SUMMER"), List.of("DAILY"), "COTTON");
        WardrobeItem bottom = wardrobe(2, "深蓝直筒牛仔裤", "BOTTOM", "JEANS", "DENIM_BLUE",
                List.of("CASUAL"), "STRAIGHT", "SOLID", List.of("ALL_SEASON"), List.of("DAILY"), "DENIM");

        OutfitRecommendationEngine.EngineResult single = engine.recommend(anchor, List.of(anchor, bottom),
                List.of(publicMatch(8L, 0.90d)), List.of(), Set.of(), request(anchor.id(), 3));
        OutfitRecommendationEngine.EngineResult result = engine.recommend(anchor, List.of(anchor, bottom),
                List.of(publicMatch(8L, 0.90d), publicMatch(9L, 0.84d)),
                List.of(), Set.of(), request(anchor.id(), 3));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().evidence()).hasSize(2);
        assertThat(result.candidates().getFirst().totalScore())
                .isGreaterThan(single.candidates().getFirst().totalScore());
    }

    @Test
    void doesNotClaimTheWardrobeIsMissingAnItemWhenPublicEvidenceIsAbsent() {
        WardrobeItem anchor = wardrobe(1, "浅灰宽松T恤", "TOP", "T_SHIRT", "LIGHT_GRAY",
                List.of("MINIMAL"), "RELAXED", "SOLID", List.of("SUMMER"), List.of("DAILY"), "COTTON");

        OutfitRecommendationEngine.EngineResult result = engine.recommend(anchor, List.of(anchor),
                List.of(), List.of(), Set.of(), request(anchor.id(), 3));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.missingItem().categoryCode()).isEmpty();
        assertThat(result.missingItem().summary()).contains("公共穿搭证据")
                .doesNotContain("缺少适合这件衣服的下装");
        assertThat(result.missingItem().confidence()).isZero();
    }

    @Test
    void normalizesChineseWardrobeAndRequestTagsBeforeDeterministicScoring() {
        WardrobeItem anchor = wardrobe(1, "浅灰宽松T恤", "上衣", "T恤", "浅灰色",
                List.of("简约", "休闲"), "宽松", "纯色", List.of("夏季"), List.of("日常"), "棉质");
        WardrobeItem bottom = wardrobe(2, "牛仔蓝直筒裤", "下装", "牛仔裤", "牛仔蓝",
                List.of("休闲"), "直筒", "纯色", List.of("四季"), List.of("日常"), "牛仔");
        OutfitRecommendationRequest request = new OutfitRecommendationRequest(
                "wechat-user", 1L, List.of("日常"), List.of("夏季"),
                "武汉 30 度", List.of("简约", "休闲"), "明天", 3);

        OutfitRecommendationEngine.EngineResult result = engine.recommend(anchor, List.of(anchor, bottom),
                List.of(publicMatch(8L, 0.91d)), List.of(), Set.of(), request);

        assertThat(result.missingItem()).isNull();
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.items().get("BOTTOM").id()).isEqualTo(2L);
            assertThat(candidate.breakdown().get("occasion")).isEqualTo(1d);
            assertThat(candidate.breakdown().get("seasonWeather")).isEqualTo(1d);
        });
    }

    @Test
    void lowersEvidenceConfidenceWhenVectorSearchFallsBackToMysqlAttributes() {
        WardrobeItem anchor = wardrobe(1, "浅灰宽松T恤", "TOP", "T_SHIRT", "LIGHT_GRAY",
                List.of("MINIMAL", "CASUAL"), "RELAXED", "SOLID",
                List.of("SUMMER"), List.of("DAILY"), "COTTON");
        WardrobeItem bottom = wardrobe(2, "深蓝直筒牛仔裤", "BOTTOM", "JEANS", "DENIM_BLUE",
                List.of("CASUAL"), "STRAIGHT", "SOLID",
                List.of("ALL_SEASON"), List.of("DAILY"), "DENIM");

        OutfitRecommendationEngine.Candidate vectorCandidate = engine.recommend(anchor, List.of(anchor, bottom),
                List.of(publicMatch(8L, 0.95d)), List.of(), Set.of(), request(anchor.id(), 1))
                .candidates().getFirst();
        OutfitRecommendationEngine.Candidate fallbackCandidate = engine.recommend(anchor, List.of(anchor, bottom),
                List.of(publicMatch(8L, -1d)), List.of(), Set.of(), request(anchor.id(), 1))
                .candidates().getFirst();

        assertThat(fallbackCandidate.breakdown().get("evidence"))
                .isLessThan(vectorCandidate.breakdown().get("evidence"));
        assertThat(fallbackCandidate.totalScore()).isLessThan(vectorCandidate.totalScore());
    }

    private static OutfitRecommendationRequest request(long anchorId, int limit) {
        return new OutfitRecommendationRequest("wechat-user", anchorId, List.of("DAILY"),
                List.of("SUMMER"), "武汉 30 度", List.of("CASUAL"), "明天", limit);
    }

    private static SemanticReferenceGarmentMatch publicMatch(long lookId, double score) {
        FashionReferenceGarment top = garment(lookId, 1, "浅灰色宽松短袖T恤", "TOP", "T_SHIRT",
                "LIGHT_GRAY", List.of("MINIMAL", "CASUAL"), "RELAXED", "SOLID",
                List.of("SUMMER"), List.of("DAILY"), List.of("COTTON"), 1);
        FashionReferenceGarment bottom = garment(lookId, 2, "牛仔蓝直筒牛仔裤", "BOTTOM", "JEANS",
                "DENIM_BLUE", List.of("CASUAL"), "STRAIGHT", "SOLID",
                List.of("ALL_SEASON"), List.of("DAILY"), List.of("DENIM"), 1);
        FashionReferenceLook look = new FashionReferenceLook(lookId, "look-" + lookId,
                "浅灰T恤 + 牛仔裤", "img_public0001", 1, "image/png", "look.png", "",
                "LOCAL_IMPORT", "LOCAL_DEVELOPMENT_ONLY", "a".repeat(64), "", "1.0.0", "{}",
                "ACTIVE", List.of(top, bottom), Instant.now(), Instant.now());
        return new SemanticReferenceGarmentMatch(look, top, score);
    }

    private static FashionReferenceGarment garment(
            long lookId, int index, String name, String parent, String category, String color,
            List<String> styles, String fit, String pattern, List<String> seasons,
            List<String> occasions, List<String> materials, int formality
    ) {
        return new FashionReferenceGarment(index, lookId, index, name, parent, category, "MENS", color,
                List.of(), List.of(), styles, fit, pattern, "H_LINE", "REGULAR", materials,
                seasons, occasions, formality, "FULL", new BigDecimal("0.95"),
                new BigDecimal("0.95"), "{}");
    }

    private static WardrobeItem wardrobe(
            long id, String name, String parent, String category, String color, List<String> styles,
            String fit, String pattern, List<String> seasons, List<String> occasions, String material
    ) {
        Instant now = Instant.now();
        return new WardrobeItem(id, 1L, "instance", name, parent, category, color, List.of(), styles,
                fit, pattern, seasons, occasions, material, "ACTIVE", "SUCCEEDED", 1,
                BigDecimal.ONE, "USER_CONFIRMED", "", "1.0.0", "{}", now, now);
    }
}
