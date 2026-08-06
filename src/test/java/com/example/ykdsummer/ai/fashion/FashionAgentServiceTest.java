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
import com.example.ykdsummer.fashion.application.FashionVisualPreviewService;
import com.example.ykdsummer.fashion.application.FashionVisualPreviewService.WardrobePreview;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        FashionAgentService service = new FashionAgentService(null, new FashionResponseFormatter(), null, null, null, null, null, null);

        String reply = service.consult("  ");

        assertTrue(reply.contains("通勤"));
        assertTrue(reply.contains("约会"));
        assertTrue(reply.contains("面试"));
    }

    @Test
    void consultFormatsPipelineResultIntoWechatCopy() {
        FashionAgentService service = new FashionAgentService(stubCoordinator(), new FashionResponseFormatter(), null, null, null, null, null, null);

        String reply = service.consult("今天去海边穿什么");

        // 完整管道结果被格式化为微信文案：最终方案 + 理由 + 备选
        assertTrue(reply.contains("这套更适合户外"));
        assertTrue(reply.contains("上衣：浅蓝亚麻衬衫"));
        assertTrue(reply.contains("为什么这样穿"));
        assertTrue(reply.contains("小建议"));
        assertTrue(reply.contains("想换个感觉的话"));
    }

    @Test
    void sendsReferenceImagesBeforeReturningText() {
        AtomicReference<ImageTaskCompletionEvent> published = new AtomicReference<>();
        FashionAgentService service = new FashionAgentService(
                stubCoordinatorWithReferenceImage(), new FashionResponseFormatter(),
                null, null, mock(ReferenceImageResolver.class), null, published::set, null);
        service.setReferenceImageDownloader(url -> new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        String reply = service.consult("今天去海边穿什么");

        // 同步发送：图片已下载并发布（先发图），文案随后可用（后发文本）。
        assertTrue(reply.contains("这套更适合户外"));
        assertNotNull(published.get());
    }

    @Test
    void schedulesReferenceImagesWithRealExecutorDoesNotThrowArrayStore() {
        // 回归 8.19：executor 装配时 submitSendUnit 走 pool.submit 返回 FutureTask，
        // allOf 聚合强转 CompletableFuture[] 曾抛 ArrayStoreException，导致整个工具失败。
        // 单测此前只覆盖 executor=null 同步分支，这里补真实 executor 分支。
        AtomicReference<ImageTaskCompletionEvent> published = new AtomicReference<>();
        ReferenceImageSendGate gate = mock(ReferenceImageSendGate.class);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            FashionAgentService service = new FashionAgentService(
                    stubCoordinatorWithReferenceImage(), new FashionResponseFormatter(),
                    null, null, mock(ReferenceImageResolver.class), null, published::set, gate);
            service.setReferenceImageDownloader(url -> new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
            service.setExecutor(pool);

            String reply = service.consult("今天去海边穿什么");

            // 不抛 ArrayStoreException，正常返回文案，且完成信号已按 userId 登记
            assertTrue(reply.contains("这套更适合户外"));
            verify(gate).track(eq("test-user"), any(CompletableFuture.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void doesNotRepublishSameReferenceOutfitWithinDedupWindow() {
        List<ImageTaskCompletionEvent> published = new ArrayList<>();
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        when(resolver.urlsFor("034")).thenReturn(List.of(
                "https://example.com/fashion-reference/outfits/034/overview.webp",
                "https://example.com/fashion-reference/outfits/034/034_1_top.png",
                "https://example.com/fashion-reference/outfits/034/034_2_bottom.png"));
        FashionAgentService service = new FashionAgentService(
                stubCoordinatorWithOutfitId("034"), new FashionResponseFormatter(),
                null, null, resolver, null, published::add, null);
        service.setReferenceImageDownloader(url -> new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        // 第一次 consult 同步发送 3 张参考图
        service.consult("今天去海边穿什么");
        assertEquals(3, published.size());

        // 用户随后说"试穿一下"触发第二次 consult，仍是同一 outfit，窗口期内不重复发送
        service.consult("试穿一下这套");
        assertEquals(3, published.size());
    }

    @Test
    void collagesTopAndBottomGarmentsIntoOneImage() throws IOException {
        List<ImageTaskCompletionEvent> published = new ArrayList<>();
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        when(resolver.urlsFor("034")).thenReturn(List.of(
                "https://example.com/fashion-reference/outfits/034/overview.webp",
                "https://example.com/fashion-reference/outfits/034/034_1_top.png",
                "https://example.com/fashion-reference/outfits/034/034_2_bottom.png"));
        when(resolver.garmentsFor("034")).thenReturn(List.of(
                new ReferenceImageResolver.GarmentImage("top", "034_1_top.png",
                        "https://example.com/fashion-reference/outfits/034/034_1_top.png"),
                new ReferenceImageResolver.GarmentImage("bottom", "034_2_bottom.png",
                        "https://example.com/fashion-reference/outfits/034/034_2_bottom.png")));
        byte[] png = tinyPng();
        FashionAgentService service = new FashionAgentService(
                stubCoordinatorWithOutfitId("034"), new FashionResponseFormatter(),
                null, null, resolver, null, published::add, null);
        service.setReferenceImageDownloader(url -> png);

        service.consult("今天去海边穿什么");

        // overview 单独一张 + top/bottom 拼成一张 = 共 2 次发送
        assertEquals(2, published.size());
        BufferedImage collage = ImageIO.read(new ByteArrayInputStream(published.get(1).imageBytes()));
        assertNotNull(collage);
        assertEquals(1, collage.getWidth()); // 两张 1x1 上下拼接
        assertEquals(2, collage.getHeight());
    }

    @Test
    void layeredOutfitWithTwoTopsSendsOnlyOverviewAndCollage() throws IOException {
        // 8.26：outfit 148 叠穿方案含两件上衣（148_1_top + 148_4_top），第二件 top 无法配对
        // 曾被单独发送 → 用户一次收到 3 张图。修复后只发 overview + top①+bottom 拼图 = 2 张。
        List<ImageTaskCompletionEvent> published = new ArrayList<>();
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        when(resolver.urlsFor("148")).thenReturn(List.of(
                "https://example.com/fashion-reference/outfits/148/overview.webp",
                "https://example.com/fashion-reference/outfits/148/148_1_top.png",
                "https://example.com/fashion-reference/outfits/148/148_2_bottom.png",
                "https://example.com/fashion-reference/outfits/148/148_4_top.png"));
        when(resolver.garmentsFor("148")).thenReturn(List.of(
                new ReferenceImageResolver.GarmentImage("top", "148_1_top.png",
                        "https://example.com/fashion-reference/outfits/148/148_1_top.png"),
                new ReferenceImageResolver.GarmentImage("bottom", "148_2_bottom.png",
                        "https://example.com/fashion-reference/outfits/148/148_2_bottom.png"),
                new ReferenceImageResolver.GarmentImage("top", "148_4_top.png",
                        "https://example.com/fashion-reference/outfits/148/148_4_top.png")));
        byte[] png = tinyPng();
        FashionAgentService service = new FashionAgentService(
                stubCoordinatorWithOutfitId("148"), new FashionResponseFormatter(),
                null, null, resolver, null, published::add, null);
        service.setReferenceImageDownloader(url -> png);

        service.consult("推荐一套适合去搭讪的穿搭");

        // overview 单独一张 + top①+bottom 拼成一张 = 2 次发送；第二件 top（148_4_top）不再单独发
        assertEquals(2, published.size());
    }

    @Test
    void fallsBackToSeparateImagesWhenCollageDecodeFails() {
        List<ImageTaskCompletionEvent> published = new ArrayList<>();
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        when(resolver.urlsFor("034")).thenReturn(List.of(
                "https://example.com/fashion-reference/outfits/034/034_1_top.png",
                "https://example.com/fashion-reference/outfits/034/034_2_bottom.png"));
        when(resolver.garmentsFor("034")).thenReturn(List.of(
                new ReferenceImageResolver.GarmentImage("top", "034_1_top.png",
                        "https://example.com/fashion-reference/outfits/034/034_1_top.png"),
                new ReferenceImageResolver.GarmentImage("bottom", "034_2_bottom.png",
                        "https://example.com/fashion-reference/outfits/034/034_2_bottom.png")));
        FashionAgentService service = new FashionAgentService(
                stubCoordinatorWithOutfitId("034"), new FashionResponseFormatter(),
                null, null, resolver, null, published::add, null);
        // 下载的是无法解码的伪图片字节：拼图失败，降级逐张发送
        service.setReferenceImageDownloader(url -> new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        service.consult("今天去海边穿什么");

        // 拼图解码失败，降级为逐张发送：top、bottom 各自单独 publish
        assertEquals(2, published.size());
    }

    @Test
    void sendsWardrobeItemImageWhenConsultMatchesWardrobeItem() {
        List<ImageTaskCompletionEvent> published = new ArrayList<>();
        WardrobeItem redTee = wardrobeItem(42L, "红色条纹T恤", "T_SHIRT", "红色", List.of(), "条纹");
        FashionVisualPreviewService previews = mock(FashionVisualPreviewService.class);
        when(previews.wardrobeItems(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new WardrobePreview(redTee, "asset-1", 1, new byte[]{1, 2, 3})));
        FashionAgentService service = new FashionAgentService(
                stubCoordinatorWithRag("1. [outfit_034] 海边参考\n\n2. 其他"), new FashionResponseFormatter(),
                null, null, mock(ReferenceImageResolver.class), null, published::add, null);
        service.setVisualPreviews(previews);

        String reply = service.consult("用我的红色条纹T恤搭配一套");

        // 匹配到衣橱单品：只发该单品自己的图片（先发图），不再发 RAG 参考图
        assertEquals(1, published.size());
        assertEquals("wardrobe_42", published.get(0).taskId());
        assertEquals(3, published.get(0).imageBytes().length);
        assertTrue(reply.contains("这套更适合户外"));
    }

    @Test
    void skipsReferenceImagesWhenWardrobeItemMatched() {
        List<ImageTaskCompletionEvent> published = new ArrayList<>();
        // 若误走参考图链路，会下载并发布 3 张参考图；衣橱匹配成功时应全部跳过
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        when(resolver.urlsFor("034")).thenReturn(List.of(
                "https://example.com/fashion-reference/outfits/034/overview.webp",
                "https://example.com/fashion-reference/outfits/034/034_1_top.png",
                "https://example.com/fashion-reference/outfits/034/034_2_bottom.png"));
        WardrobeItem redTee = wardrobeItem(7L, "红色条纹T恤", "T_SHIRT", "红色", List.of(), "条纹");
        FashionVisualPreviewService previews = mock(FashionVisualPreviewService.class);
        when(previews.wardrobeItems(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new WardrobePreview(redTee, "asset-1", 1, new byte[]{1, 2, 3})));
        FashionAgentService service = new FashionAgentService(
                stubCoordinatorWithOutfitId("034"), new FashionResponseFormatter(),
                null, null, resolver, null, published::add, null);
        service.setVisualPreviews(previews);
        service.setReferenceImageDownloader(url -> new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        service.consult("用红色条纹T恤搭配一套");

        // 衣橱单品图优先，参考图完全不发送
        assertEquals(1, published.size());
        assertEquals("wardrobe_7", published.get(0).taskId());
    }

    @Test
    void matchesWardrobeItemWithEncodedAttributes() {
        // 真实数据：colorPrimary/patternCode 是英文编码（RED/STRIPES），用户输入是中文（"红色"）
        List<ImageTaskCompletionEvent> published = new ArrayList<>();
        WardrobeItem redTee = wardrobeItem(1L, "红色条纹T恤", "T_SHIRT", "RED", List.of(), "STRIPES");
        FashionVisualPreviewService previews = mock(FashionVisualPreviewService.class);
        when(previews.wardrobeItems(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new WardrobePreview(redTee, "asset-1", 1, new byte[]{1, 2, 3})));
        FashionAgentService service = new FashionAgentService(
                stubCoordinatorWithRag("海边穿搭参考"), new FashionResponseFormatter(),
                null, null, mock(ReferenceImageResolver.class), null, published::add, null);
        service.setVisualPreviews(previews);

        String reply = service.consult("用红色T恤帮我搭一套");

        assertEquals(1, published.size());
        assertEquals("wardrobe_1", published.get(0).taskId());
        assertTrue(reply.contains("这套更适合户外"));
    }

    private static WardrobeItem wardrobeItem(long id, String displayName, String categoryCode, String color,
                                             List<String> secondaryColors, String patternCode) {
        return new WardrobeItem(
                id, 1L, "inst", displayName, "", categoryCode, color, secondaryColors, List.of("CASUAL"), "",
                patternCode, List.of("SUMMER"), List.of("DAILY"), "棉", "ACTIVE", "COMPLETE", 1,
                BigDecimal.ONE, "upload", "", "1.0.0", "{}", Instant.now(), Instant.now());
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
                null, null, null, null, null, null) {
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
                null, null, null, null, null, null) {
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

    /** 生成 1x1 白色 PNG 字节，用于拼图测试的下载替身。 */
    private static byte[] tinyPng() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
