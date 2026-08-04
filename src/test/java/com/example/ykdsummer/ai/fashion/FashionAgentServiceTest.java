package com.example.ykdsummer.ai.fashion;

import com.example.ykdsummer.ai.fashion.agent.AgentCoordinator;
import com.example.ykdsummer.ai.fashion.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.model.CoordinatorOutput;
import com.example.ykdsummer.ai.fashion.model.CriticOutput;
import com.example.ykdsummer.ai.fashion.model.FashionRequest;
import com.example.ykdsummer.ai.fashion.model.FashionResult;
import com.example.ykdsummer.ai.fashion.model.StylistOutput;
import com.example.ykdsummer.ai.fashion.model.TrendOutput;
import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import com.example.ykdsummer.ai.tool.ImageTaskCompletionEvent;
import com.example.ykdsummer.ai.tool.ImageTaskCompletionPublisher;
import com.example.ykdsummer.ai.tool.ImageTaskRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FashionAgentServiceTest {

    @BeforeEach
    void bindSessionContext() {
        AgentSessionContext.set("test-user", "test-session");
    }

    @AfterEach
    void clearSessionContext() {
        AgentSessionContext.clear();
    }

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
        FashionAgentService service = new FashionAgentService(null, new FashionResponseFormatter(), null, null, null, null, null);

        String reply = service.consult("  ");

        assertTrue(reply.contains("通勤"));
        assertTrue(reply.contains("约会"));
        assertTrue(reply.contains("面试"));
    }

    @Test
    void consultFormatsPipelineResultIntoWechatCopy() {
        FashionAgentService service = new FashionAgentService(stubCoordinator(), new FashionResponseFormatter(), null, null, null, null, null);

        String reply = service.consult("今天去海边穿什么");

        // 完整管道结果被格式化为微信文案：最终方案 + 理由 + 备选
        assertTrue(reply.contains("这套更适合户外"));
        assertTrue(reply.contains("上衣：浅蓝亚麻衬衫"));
        assertTrue(reply.contains("为什么这样穿"));
        assertTrue(reply.contains("小建议"));
        assertTrue(reply.contains("想换个感觉的话"));
    }

    @Test
    void consultReturnsTextBeforeReferenceImagesAreDownloaded() {
        List<Runnable> scheduled = new ArrayList<>();
        ImageTaskRunner runner = scheduled::add;
        AtomicReference<ImageTaskCompletionEvent> published = new AtomicReference<>();
        FashionAgentService service = new FashionAgentService(
                stubCoordinatorWithReferenceImage(), new FashionResponseFormatter(),
                null, null, mock(ReferenceImageResolver.class), runner, published::set);

        String reply = service.consult("今天去海边穿什么");

        // 文案立即可用，图片下载被异步调度且尚未发布完成事件。
        assertTrue(reply.contains("这套更适合户外"));
        assertEquals(1, scheduled.size());
        assertNull(published.get());
    }

    @Test
    void doesNotRepublishSameReferenceOutfitWithinDedupWindow() {
        List<Runnable> scheduled = new ArrayList<>();
        ImageTaskRunner runner = scheduled::add;
        AtomicReference<ImageTaskCompletionEvent> published = new AtomicReference<>();
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        when(resolver.urlsFor("034")).thenReturn(List.of(
                "https://example.com/fashion-reference/outfits/034/overview.webp",
                "https://example.com/fashion-reference/outfits/034/034_1_top.png",
                "https://example.com/fashion-reference/outfits/034/034_2_bottom.png"));
        FashionAgentService service = new FashionAgentService(
                stubCoordinatorWithOutfitId("034"), new FashionResponseFormatter(),
                null, null, resolver, runner, published::set);

        // 第一次 consult 发布 3 张参考图
        service.consult("今天去海边穿什么");
        assertEquals(3, scheduled.size());

        // 用户随后说"试穿一下"触发第二次 consult，仍是同一 outfit，窗口期内不重复补发
        service.consult("试穿一下这套");
        assertEquals(3, scheduled.size());
    }

    private static AgentCoordinator stubCoordinator() {
        return stubCoordinatorWithRag("海边穿搭参考");
    }

    private static AgentCoordinator stubCoordinatorWithReferenceImage() {
        return stubCoordinatorWithRag("1. 参考案例\n"
                + "https://example.com/fashion-reference/outfits/001.webp\n"
                + "\n2. 其他知识片段");
    }

    /** Coordinator 最终方案引用固定 outfit 编号（参考图按编号去重）。 */
    private static AgentCoordinator stubCoordinatorWithOutfitId(String outfitId) {
        return new AgentCoordinator(null, null, null, null, null, null,
                null, null, null, null, null) {
            @Override
            public FashionResult process(FashionRequest request) {
                return new FashionResult(
                        true,
                        new CoordinatorOutput(
                                new CoordinatorOutput.FinalRecommendation(1, "更适合海边", Map.of()),
                                new CoordinatorOutput.RefinedOutfit("浅蓝亚麻衬衫", "米白直筒短裤", "白色帆布鞋", "草编包", outfitId),
                                "亚麻衬衫透气。",
                                List.of()
                        ),
                        StylistOutput.empty(),
                        CriticOutput.empty(),
                        TrendOutput.neutral(),
                        "1. [outfit_034] 海边参考\n\n2. 其他",
                        analyzed("BEACH"),
                        null,
                        false
                );
            }
        };
    }

    private static AgentCoordinator stubCoordinatorWithRag(String ragContext) {
        return new AgentCoordinator(null, null, null, null, null, null,
                null, null, null, null, null) {
            @Override
            public FashionResult process(FashionRequest request) {
                return new FashionResult(
                        true,
                        new CoordinatorOutput(
                                new CoordinatorOutput.FinalRecommendation(1, "更适合海边", Map.of("2", "偏正式")),
                                new CoordinatorOutput.RefinedOutfit("浅蓝亚麻衬衫", "米白直筒短裤", "白色帆布鞋", "草编包", null),
                                "亚麻衬衫透气，浅蓝和米白贴合海边场景。",
                                List.of("白天注意防晒。")
                        ),
                        new StylistOutput(List.of(
                                new StylistOutput.OutfitSuggestion(1, "清爽度假风",
                                        new StylistOutput.Outfit("浅蓝亚麻衬衫", "米白短裤", "白色帆布鞋", "草编包"),
                                        "蓝白", "轻松清爽", List.of("海边"), "不挑身形", null),
                                new StylistOutput.OutfitSuggestion(2, "轻户外风",
                                        new StylistOutput.Outfit("速干T恤", "卡其短裤", "凉鞋", "棒球帽"),
                                        "卡其", "更方便活动", List.of("户外"), "适合多数体型", null)
                        )),
                        CriticOutput.empty(),
                        TrendOutput.neutral(),
                        ragContext,
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
