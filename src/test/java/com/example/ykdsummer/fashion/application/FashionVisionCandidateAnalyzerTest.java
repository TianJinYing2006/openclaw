package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.ImageInspectionService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.ClothingCompletenessStatus;
import com.example.ykdsummer.fashion.domain.FashionPersonTemplateStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FashionVisionCandidateAnalyzerTest {

    @Test
    void acceptsOnlyACompleteClearPersonPhotoForTryOnTemplate() {
        FashionPersonTemplateAnalyzer analyzer = new FashionPersonTemplateAnalyzer(mock(ImageInspectionService.class), new ObjectMapper());

        var ready = analyzer.parse("""
                {"status":"READY","summary":"单人全身正面清晰入镜","retakeGuidance":"","confidence":0.91}
                """);
        var retake = analyzer.parse("""
                {"status":"READY","summary":"人物腿部被裁切","retakeGuidance":"","confidence":0.42}
                """);

        assertEquals(FashionPersonTemplateStatus.READY, ready.status());
        assertEquals(FashionPersonTemplateStatus.RETAKE_REQUIRED, retake.status());
        assertTrue(retake.retakeGuidance().contains("从头到脚"));
    }

    @Test
    void parsesStructuredCandidatesAndForcesRetakeForLowQualityGarment() {
        FashionVisionCandidateAnalyzer analyzer = new FashionVisionCandidateAnalyzer(mock(ImageInspectionService.class), new ObjectMapper());

        WardrobePhotoAnalyzer.AnalysisResult result = analyzer.parse("""
                ```json
                {"summary":"two garments","candidates":[
                  {"displayName":"white shirt","categoryCode":"SHIRT","colorPrimary":"WHITE",
                   "secondaryColors":[],"styleTags":["MINIMAL"],"fitCode":"RELAXED","seasonTags":["SPRING"],
                   "attributes":{"patternCode":"SOLID"},"confidence":0.94,"qualityScore":0.88,
                   "completenessStatus":"READY","retakeGuidance":""},
                  {"displayName":"covered trousers","categoryCode":"PANTS","colorPrimary":"BLACK",
                   "secondaryColors":[],"styleTags":[],"fitCode":"","seasonTags":[],"attributes":{},
                   "confidence":0.71,"qualityScore":0.42,"completenessStatus":"READY","retakeGuidance":""}
                ]}
                ```
                """);

        assertEquals("two garments", result.summary());
        assertEquals(2, result.candidates().size());
        assertEquals("SHIRT", result.candidates().getFirst().categoryCode());
        assertEquals(ClothingCompletenessStatus.READY, result.candidates().getFirst().completenessStatus());
        assertEquals("STRAIGHT_PANTS", result.candidates().get(1).categoryCode());
        assertEquals(ClothingCompletenessStatus.RETAKE_REQUIRED, result.candidates().get(1).completenessStatus());
        assertTrue(result.candidates().get(1).retakeGuidance().contains("主要轮廓"));
    }

    @Test
    void acceptsAWornGarmentWhenItsMainSilhouetteIsVisible() {
        FashionVisionCandidateAnalyzer analyzer = new FashionVisionCandidateAnalyzer(mock(ImageInspectionService.class), new ObjectMapper());

        WardrobePhotoAnalyzer.AnalysisResult result = analyzer.parse("""
                {"summary":"person wearing an oversized polo and wide-leg trousers","candidates":[
                  {"displayName":"gray oversized polo","categoryCode":"T_SHIRT","colorPrimary":"GRAY",
                   "secondaryColors":[],"styleTags":["CASUAL"],"fitCode":"RELAXED","seasonTags":["SUMMER"],
                   "attributes":{"visibility":"worn; lower hem partly overlaps trousers","patternCode":"SOLID"},
                   "confidence":0.82,"qualityScore":0.58,"completenessStatus":"READY","retakeGuidance":""},
                  {"displayName":"black wide-leg trousers","categoryCode":"PANTS","colorPrimary":"BLACK",
                   "secondaryColors":[],"styleTags":[],"fitCode":"RELAXED","seasonTags":[],
                   "attributes":{"visibility":"waist covered by top; legs and hems visible"},
                   "confidence":0.79,"qualityScore":0.54,"completenessStatus":"READY","retakeGuidance":""}
                ]}
                """);

        assertEquals(2, result.candidates().size());
        assertEquals(ClothingCompletenessStatus.READY, result.candidates().getFirst().completenessStatus());
        assertEquals("STRAIGHT_PANTS", result.candidates().get(1).categoryCode());
        assertEquals(ClothingCompletenessStatus.READY, result.candidates().get(1).completenessStatus());
    }

    @Test
    void reusesDetailedSavedVisualSummaryBeforeCallingVisionAgain() {
        ImageInspectionService inspection = mock(ImageInspectionService.class);
        FashionVisionCandidateAnalyzer analyzer = new FashionVisionCandidateAnalyzer(inspection, new ObjectMapper());
        StoredImage source = new StoredImage("img_123456789012", 1, Path.of("summary-only.jpg"), "", null,
                Instant.now(), "image/jpeg", "uploaded", """
                一位年轻男性穿着灰色宽松短袖 Polo 衫，衣长盖过臀部；下装是一条黑色阔腿长裤，
                腰部被上衣覆盖但双腿和裤脚清晰可见；脚穿黑色厚底鞋，只露出鞋头部分。整体休闲简约。
                """);

        WardrobePhotoAnalyzer.AnalysisResult result = analyzer.analyze(source);

        assertEquals(3, result.candidates().size());
        assertEquals("T_SHIRT", result.candidates().getFirst().categoryCode());
        assertEquals("灰色宽松Polo衫", result.candidates().getFirst().displayName());
        assertEquals(ClothingCompletenessStatus.READY, result.candidates().getFirst().completenessStatus());
        assertTrue(result.candidates().getFirst().seasonTags().contains("SUMMER"));
        assertEquals("STRAIGHT_PANTS", result.candidates().get(1).categoryCode());
        assertEquals(ClothingCompletenessStatus.READY, result.candidates().get(1).completenessStatus());
        assertEquals("SHOES", result.candidates().get(2).categoryCode());
        assertEquals(ClothingCompletenessStatus.RETAKE_REQUIRED, result.candidates().get(2).completenessStatus());
    }
}
