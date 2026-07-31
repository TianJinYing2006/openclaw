package com.example.ykdsummer.ai.fashion;

import com.example.ykdsummer.ai.fashion.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.model.CoordinatorOutput;
import com.example.ykdsummer.ai.fashion.model.CriticOutput;
import com.example.ykdsummer.ai.fashion.model.FashionResult;
import com.example.ykdsummer.ai.fashion.model.StylistOutput;
import com.example.ykdsummer.ai.fashion.model.TrendOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FashionResponseFormatterTest {

    private final FashionResponseFormatter formatter = new FashionResponseFormatter();

    @Test
    void formatsFinalRecommendationForWechatReading() {
        FashionResult result = new FashionResult(
                true,
                new CoordinatorOutput(
                        new CoordinatorOutput.FinalRecommendation(1, "更适合海边", Map.of("2", "偏正式")),
                        new CoordinatorOutput.RefinedOutfit("浅蓝亚麻衬衫", "米白直筒短裤", "白色帆布鞋", "草编包"),
                        "亚麻衬衫透气，浅蓝和米白能贴合海边场景，同时比普通T恤更有度假感。",
                        List.of("白天注意防晒。", "晚上有风可以带一件薄外套。", "鞋子优先选不怕沙的款式。", "这条不会展示。")
                ),
                new StylistOutput(List.of(
                        new StylistOutput.OutfitSuggestion(1, "清爽度假风",
                                new StylistOutput.Outfit("浅蓝亚麻衬衫", "米白短裤", "白色帆布鞋", "草编包"),
                                "蓝白", "轻松清爽", List.of("海边"), "不挑身形"),
                        new StylistOutput.OutfitSuggestion(2, "轻户外风",
                                new StylistOutput.Outfit("速干T恤", "卡其短裤", "凉鞋", "棒球帽"),
                                "卡其", "更方便活动", List.of("户外"), "适合多数体型"),
                        new StylistOutput.OutfitSuggestion(3, "简约休闲风",
                                new StylistOutput.Outfit("白T", "牛仔短裤", "运动鞋", ""),
                                "蓝白", "日常好穿", List.of("日常"), "不挑身形")
                )),
                CriticOutput.empty(),
                TrendOutput.neutral(),
                "海边穿搭参考",
                analyzed("beach"),
                null,
                false
        );

        String text = formatter.format(result);

        assertTrue(text.contains("这套更适合户外"));
        assertTrue(text.contains("上衣：浅蓝亚麻衬衫"));
        assertTrue(text.contains("为什么这样穿"));
        assertTrue(text.contains("小建议"));
        assertTrue(text.contains("想换个感觉的话"));
        assertFalse(text.contains("这条不会展示"));
    }

    @Test
    void hidesInternalDegradeReasonWhenCoordinatorExists() {
        FashionResult result = new FashionResult(
                false,
                new CoordinatorOutput(
                        new CoordinatorOutput.FinalRecommendation(1, "降级首选", Map.of()),
                        new CoordinatorOutput.RefinedOutfit("白色衬衫", "深色西裤", "乐福鞋", ""),
                        "稳妥、干净，适合通勤。",
                        List.of()
                ),
                new StylistOutput(List.of()),
                CriticOutput.empty(),
                TrendOutput.neutral(),
                "",
                analyzed("work"),
                "Coordinator 超时，降级为 Stylist 首选方案",
                true
        );

        String text = formatter.format(result);

        assertTrue(text.contains("这套更适合通勤"));
        assertFalse(text.contains("Coordinator 超时"));
        assertFalse(text.contains("降级"));
    }

    @Test
    void safetyFallbackUsesFormalScene() {
        String text = formatter.format(FashionResult.safetyFallback("FORMAL_EVENT", analyzed("wedding")));

        assertTrue(text.contains("通用正式场合穿搭方案"));
        assertTrue(text.contains("白色基础T恤"));
    }

    private static AnalyzedQuery analyzed(String scene) {
        return new AnalyzedQuery(
                "今天穿什么",
                List.of("今天穿什么", scene),
                new AnalyzedQuery.QueryParams(scene, "SUMMER", 2, "unknown", "")
        );
    }
}
