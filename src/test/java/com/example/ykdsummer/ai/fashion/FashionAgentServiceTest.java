package com.example.ykdsummer.ai.fashion;

import com.example.ykdsummer.ai.fashion.agent.AgentCoordinator;
import com.example.ykdsummer.ai.fashion.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.model.CoordinatorOutput;
import com.example.ykdsummer.ai.fashion.model.CriticOutput;
import com.example.ykdsummer.ai.fashion.model.FashionRequest;
import com.example.ykdsummer.ai.fashion.model.FashionResult;
import com.example.ykdsummer.ai.fashion.model.StylistOutput;
import com.example.ykdsummer.ai.fashion.model.TrendOutput;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FashionAgentServiceTest {

    @Test
    void exposesFashionConsultantAsSpringAiTool() throws NoSuchMethodException {
        Method consult = FashionAgentService.class.getMethod("consult", String.class);
        Tool tool = consult.getAnnotation(Tool.class);

        assertEquals("fashion_consultant", tool.name());
        assertTrue(tool.description().contains("穿什么"));
        assertTrue(tool.description().contains("海边/婚礼/通勤"));
        assertTrue(tool.description().contains("多Agent"));
    }

    @Test
    void blankInputReturnsGuidanceWithoutCallingPipeline() {
        FashionAgentService service = new FashionAgentService(null, new FashionResponseFormatter());

        String reply = service.consult("  ");

        assertTrue(reply.contains("通勤"));
        assertTrue(reply.contains("约会"));
        assertTrue(reply.contains("面试"));
    }

    @Test
    void consultFormatsPipelineResultIntoWechatCopy() {
        FashionAgentService service = new FashionAgentService(stubCoordinator(), new FashionResponseFormatter());

        String reply = service.consult("今天去海边穿什么");

        // 完整管道结果被格式化为微信文案：最终方案 + 理由 + 备选
        assertTrue(reply.contains("这套更适合户外"));
        assertTrue(reply.contains("上衣：浅蓝亚麻衬衫"));
        assertTrue(reply.contains("为什么这样穿"));
        assertTrue(reply.contains("小建议"));
        assertTrue(reply.contains("想换个感觉的话"));
    }

    private static AgentCoordinator stubCoordinator() {
        return new AgentCoordinator(null, null, null, null, null, null) {
            @Override
            public FashionResult process(FashionRequest request) {
                return new FashionResult(
                        true,
                        new CoordinatorOutput(
                                new CoordinatorOutput.FinalRecommendation(1, "更适合海边", Map.of("2", "偏正式")),
                                new CoordinatorOutput.RefinedOutfit("浅蓝亚麻衬衫", "米白直筒短裤", "白色帆布鞋", "草编包"),
                                "亚麻衬衫透气，浅蓝和米白贴合海边场景。",
                                List.of("白天注意防晒。")
                        ),
                        new StylistOutput(List.of(
                                new StylistOutput.OutfitSuggestion(1, "清爽度假风",
                                        new StylistOutput.Outfit("浅蓝亚麻衬衫", "米白短裤", "白色帆布鞋", "草编包"),
                                        "蓝白", "轻松清爽", List.of("海边"), "不挑身形"),
                                new StylistOutput.OutfitSuggestion(2, "轻户外风",
                                        new StylistOutput.Outfit("速干T恤", "卡其短裤", "凉鞋", "棒球帽"),
                                        "卡其", "更方便活动", List.of("户外"), "适合多数体型")
                        )),
                        CriticOutput.empty(),
                        TrendOutput.neutral(),
                        "海边穿搭参考",
                        analyzed("BEACH"),
                        null,
                        false
                );
            }
        };
    }

    private static AnalyzedQuery analyzed(String scene) {
        return new AnalyzedQuery(
                "今天去海边穿什么",
                List.of("今天去海边穿什么", scene),
                new AnalyzedQuery.QueryParams(scene, "SUMMER", 2, "unknown", "")
        );
    }
}
