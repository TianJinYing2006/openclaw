package com.example.ykdsummer.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.orchestration.BoundedToolCallingManager;
import com.example.ykdsummer.ai.orchestration.ToolRegistry;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.application.FashionWardrobeIngestionService;
import com.example.ykdsummer.fashion.tool.FashionWardrobeIntakeTools;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 网关"穿搭推荐意图"检测：模型漏调 fashion_consultant 时自动补调的触发条件。
 */
class SpringAiChatCompletionsGatewayAutoConsultTest {

    @Test
    void detectsPlainRecommendationIntent() {
        assertTrue(SpringAiChatCompletionsGateway.hasFashionConsultIntent("推荐一套适合今天在杭州打羽毛球的穿搭"));
        assertTrue(SpringAiChatCompletionsGateway.hasFashionConsultIntent("帮我搭配一套海边穿的"));
        assertTrue(SpringAiChatCompletionsGateway.hasFashionConsultIntent("明天面试穿什么好"));
        assertTrue(SpringAiChatCompletionsGateway.hasFashionConsultIntent("上班通勤怎么穿"));
        assertTrue(SpringAiChatCompletionsGateway.hasFashionConsultIntent("给我搭一套运动装"));
    }

    @Test
    void ignoresWardrobeTryOnAndImageOperations() {
        assertFalse(SpringAiChatCompletionsGateway.hasFashionConsultIntent("试穿一下"));
        assertFalse(SpringAiChatCompletionsGateway.hasFashionConsultIntent("把这张照片放进我的衣橱"));
        assertFalse(SpringAiChatCompletionsGateway.hasFashionConsultIntent("这件上衣试试看"));
        assertFalse(SpringAiChatCompletionsGateway.hasFashionConsultIntent("帮我存一张图片到衣橱"));
        assertFalse(SpringAiChatCompletionsGateway.hasFashionConsultIntent("那条裤子能配什么"));
    }

    @Test
    void ignoresNonFashionChat() {
        assertFalse(SpringAiChatCompletionsGateway.hasFashionConsultIntent("推荐一部电影"));
        assertFalse(SpringAiChatCompletionsGateway.hasFashionConsultIntent("今天杭州天气怎么样"));
        assertFalse(SpringAiChatCompletionsGateway.hasFashionConsultIntent(""));
        assertFalse(SpringAiChatCompletionsGateway.hasFashionConsultIntent(null));
    }

    @Test
    void detectsTryOnIntent() {
        assertTrue(SpringAiChatCompletionsGateway.hasTryOnIntent("试穿一下"));
        assertTrue(SpringAiChatCompletionsGateway.hasTryOnIntent("帮我穿一下这套"));
        assertTrue(SpringAiChatCompletionsGateway.hasTryOnIntent("看看上身效果"));
        assertTrue(SpringAiChatCompletionsGateway.hasTryOnIntent("试试这套"));
        assertTrue(SpringAiChatCompletionsGateway.hasTryOnIntent("穿上看看"));
        // 8.27：换装表达（"换上/换一下/换这身"）按试穿意图提供试穿工具，避免模型空承诺试穿却无工具可调
        assertTrue(SpringAiChatCompletionsGateway.hasTryOnIntent("好,我要是换一下"));
        assertTrue(SpringAiChatCompletionsGateway.hasTryOnIntent("换上这套试试"));
        assertTrue(SpringAiChatCompletionsGateway.hasTryOnIntent("把这身换上"));
    }

    @Test
    void changingToAnotherOutfitStillGoesThroughRecommendation() {
        // "换一套/再换一套"是换推荐意图，不应被新增的换装试穿词误伤成试穿
        assertFalse(SpringAiChatCompletionsGateway.hasTryOnIntent("再换一套"));
        Set<String> groups = SpringAiChatCompletionsGateway.toolsForPrompt("再换一套");
        assertFalse(groups.contains(ToolRegistry.GROUP_TRYON),
                "再换一套不应命中试穿组: " + groups);
        // "换一套试试看"含"试试"= 换推荐后想试穿，挂试穿组是合理行为
        assertTrue(SpringAiChatCompletionsGateway.hasTryOnIntent("换一套试试看"));
    }

    @Test
    void ignoresTryOnNonExecutionAndInterview() {
        assertFalse(SpringAiChatCompletionsGateway.hasTryOnIntent("把衣柜里的衣服试穿一下"));
        assertFalse(SpringAiChatCompletionsGateway.hasTryOnIntent("怎么试穿"));
        assertFalse(SpringAiChatCompletionsGateway.hasTryOnIntent("试穿收费吗"));
        assertFalse(SpringAiChatCompletionsGateway.hasTryOnIntent("面试穿什么"));
        assertFalse(SpringAiChatCompletionsGateway.hasTryOnIntent("这件衣服试试看"));
        assertFalse(SpringAiChatCompletionsGateway.hasTryOnIntent(""));
        assertFalse(SpringAiChatCompletionsGateway.hasTryOnIntent(null));
    }

    @Test
    void routesToolGroupsByIntent() {
        // 推荐意图 → 核心 + 试穿组（推荐后随时衔接试穿）
        Set<String> rec = SpringAiChatCompletionsGateway.toolsForPrompt("推荐一套婚礼穿搭");
        assertTrue(rec.contains(ToolRegistry.GROUP_CORE));
        assertTrue(rec.contains(ToolRegistry.GROUP_TRYON));
        assertFalse(rec.contains(ToolRegistry.GROUP_WARDROBE_INTAKE));
        assertFalse(rec.contains(ToolRegistry.GROUP_REMINDER));
        // 衣橱单品试穿 → 试穿 + 衣橱查看
        Set<String> tryon = SpringAiChatCompletionsGateway.toolsForPrompt("试一下灰色T恤");
        assertTrue(tryon.contains(ToolRegistry.GROUP_TRYON));
        assertTrue(tryon.contains(ToolRegistry.GROUP_WARDROBE_VIEW));
        // 入库 → 入库组 + 衣橱查看
        Set<String> intake = SpringAiChatCompletionsGateway.toolsForPrompt("把这张照片放进我的衣橱");
        assertTrue(intake.contains(ToolRegistry.GROUP_WARDROBE_INTAKE));
        assertTrue(intake.contains(ToolRegistry.GROUP_WARDROBE_VIEW));
        // "衣柜"说法同样命中入库组（8.22 修复：用户说"加入衣柜"而非"加入衣橱"）
        Set<String> intakeWardrobe = SpringAiChatCompletionsGateway.toolsForPrompt("帮我加入衣柜");
        assertTrue(intakeWardrobe.contains(ToolRegistry.GROUP_WARDROBE_INTAKE));
        assertTrue(intakeWardrobe.contains(ToolRegistry.GROUP_WARDROBE_VIEW));
        // 候选确认表达同样命中入库组（8.22 补充：选完候选后"只要牛仔裤"也能挂载抠图工具）
        Set<String> confirm = SpringAiChatCompletionsGateway.toolsForPrompt("只要牛仔裤");
        assertTrue(confirm.contains(ToolRegistry.GROUP_WARDROBE_INTAKE));
        assertTrue(SpringAiChatCompletionsGateway.toolsForPrompt("选第一件")
                .contains(ToolRegistry.GROUP_WARDROBE_INTAKE));
        assertTrue(SpringAiChatCompletionsGateway.toolsForPrompt("这几件都要")
                .contains(ToolRegistry.GROUP_WARDROBE_INTAKE));
        // 提醒 → 提醒组
        Set<String> remind = SpringAiChatCompletionsGateway.toolsForPrompt("明天早上8点提醒我");
        assertTrue(remind.contains(ToolRegistry.GROUP_REMINDER));
        // 默认（天气闲聊）→ 仅核心
        Set<String> def = SpringAiChatCompletionsGateway.toolsForPrompt("今天天气怎么样");
        assertEquals(1, def.size());
        assertTrue(def.contains(ToolRegistry.GROUP_CORE));
        // 空输入 → 仅核心
        assertEquals(1, SpringAiChatCompletionsGateway.toolsForPrompt(null).size());
    }

    @Test
    void routesIgnoreInternalWorkflowContext() {
        // 生产 prompt = 用户消息 + [内部...] 工作流上下文（含"试穿/衣橱/衣服"等词），路由必须只看用户消息
        String internalContext = "\n\n[内部最近穿搭推荐方案：仅用于理解\"试穿/这套\"指代]\n"
                + "1. 用户说\"试穿/穿一下\"时用该编号调用 virtual_try_on_reference_outfit。\n"
                + "[/内部最近穿搭推荐方案]\n"
                + "[内部衣橱流程状态：来自 MySQL]\n用户尚未完成的衣橱动作：搜索\"衣服\"。\n[/内部衣橱流程状态]";
        String prompt = "推荐一套约会穿搭" + internalContext;

        Set<String> groups = SpringAiChatCompletionsGateway.toolsForPrompt(prompt);
        // 推荐意图只应命中核心 + 试穿组，不被内部上下文里的"试穿/衣橱"词带偏
        assertTrue(groups.contains(ToolRegistry.GROUP_TRYON));
        assertFalse(groups.contains(ToolRegistry.GROUP_WARDROBE_VIEW));
        assertFalse(groups.contains(ToolRegistry.GROUP_WARDROBE_INTAKE));
        assertFalse(groups.contains(ToolRegistry.GROUP_REMINDER));

        // 剥离函数本身：内部块被移除，用户消息原样保留
        String stripped = SpringAiChatCompletionsGateway.stripInternalContext(prompt);
        assertEquals("推荐一套约会穿搭", stripped);
        assertEquals("推荐一套约会穿搭",
                SpringAiChatCompletionsGateway.stripInternalContext("推荐一套约会穿搭"));
    }

    @Test
    void autoWardrobeIntakeTriggersOnlyWhenIntakeIntentIsUnhandled() {
        // 入库意图 + 本轮未调穿搭域工具 → 需要兜底补调 analyze_wardrobe_photo
        assertTrue(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "加入衣柜"));
        assertTrue(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "把这张照片放进我的衣橱"));
        assertTrue(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "只要牛仔裤"));
        // 内部工作流上下文（含"衣橱/衣服"词）不应带偏意图判定
        String internalContext = "\n\n[内部衣橱流程状态：来自 MySQL]\n用户尚未完成的衣橱动作：搜索\"衣服\"。\n[/内部衣橱流程状态]";
        assertTrue(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "加入衣柜" + internalContext));
        // 已调过入库/穿搭域工具 → 链路在进行中，不再重复补调
        assertFalse(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(
                Set.of("analyze_wardrobe_photo"), "加入衣柜"));
        assertFalse(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(
                Set.of("search_wardrobe"), "加入衣柜"));
        assertFalse(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(
                Set.of("virtual_try_on_reference_outfit"), "加入衣柜"));
        // 非入库意图 → 不触发
        assertFalse(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "推荐一套婚礼穿搭"));
        assertFalse(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "今天天气怎么样"));
        assertFalse(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), ""));
        assertFalse(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), null));
    }

    @Test
    void detectsWardrobeItemIntent() {
        assertTrue(SpringAiChatCompletionsGateway.hasWardrobeItemIntent("试一下灰色T恤"));
        assertTrue(SpringAiChatCompletionsGateway.hasWardrobeItemIntent("穿一下那件衬衫"));
        assertTrue(SpringAiChatCompletionsGateway.hasWardrobeItemIntent("试试这件"));
        // 无单品指向的纯试穿 → false
        assertFalse(SpringAiChatCompletionsGateway.hasWardrobeItemIntent("试穿一下"));
        assertFalse(SpringAiChatCompletionsGateway.hasWardrobeItemIntent("穿起来看看"));
        // 衣橱语境被排除（走 wardrobe_item 链路本身）
        assertFalse(SpringAiChatCompletionsGateway.hasWardrobeItemIntent("把衣柜里的衣服试穿一下"));
        assertFalse(SpringAiChatCompletionsGateway.hasWardrobeItemIntent(""));
        assertFalse(SpringAiChatCompletionsGateway.hasWardrobeItemIntent(null));
    }

    @Test
    void autoInvokesWardrobePhotoAnalysisWhenModelOnlyPromises() throws Exception {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        String userId = "managed:11111111-1111-1111-1111-111111111111:wechat-user";
        collector.begin(userId);
        FashionWardrobeIntakeTools intakeTools = new FashionWardrobeIntakeTools(ingestion, collector, AiTraceLogger.disabled());
        when(ingestion.candidatesForPhoto(eq(userId), eq("img_recent"), isNull())).thenReturn(List.of());
        when(ingestion.submitPhotoAnalysis(eq(userId), eq("img_recent"), isNull())).thenReturn(true);

        SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                mock(org.springframework.ai.chat.model.ChatModel.class), new AiProperties(),
                new Object[]{intakeTools}, collector, AiTraceLogger.disabled());
        BoundedToolCallingManager calls = mock(BoundedToolCallingManager.class);
        when(calls.calledToolNames()).thenReturn(Set.of());
        LocalImageAssetStore store = mock(LocalImageAssetStore.class);
        when(store.recent(eq(userId), eq(1))).thenReturn(List.of(new LocalImageAssetStore.StoredImage(
                "img_recent", 1, null, "", "", Instant.now(), "image/jpeg", "uploaded", "")));
        ToolRegistry registry = mock(ToolRegistry.class);
        Method method = FashionWardrobeIntakeTools.class.getMethod("analyzeWardrobePhoto", String.class, Integer.class);
        when(registry.find("analyze_wardrobe_photo"))
                .thenReturn(Optional.of(new ToolRegistry.ToolEntry("analyze_wardrobe_photo", "", intakeTools, method)));
        setGatewayField(gateway, "toolCallingManager", calls);
        setGatewayField(gateway, "imageStore", store);
        setGatewayField(gateway, "toolRegistry", registry);

        String result = invokeAutoWardrobeIntake(gateway, userId, "加入衣柜", "好的，正在为你提取这套穿搭～");

        assertTrue(result.contains("正在为你提取"));
        assertTrue(result.contains("正在识别图片中"));
        verify(ingestion).submitPhotoAnalysis(userId, "img_recent", null);
        collector.finish();
    }

    @Test
    void autoWardrobeIntakeSkipsWhenPhotoAnalysisWasAlreadyCalled() throws Exception {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        String userId = "managed:11111111-1111-1111-1111-111111111111:wechat-user";
        collector.begin(userId);
        FashionWardrobeIntakeTools intakeTools = new FashionWardrobeIntakeTools(ingestion, collector, AiTraceLogger.disabled());
        SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                mock(org.springframework.ai.chat.model.ChatModel.class), new AiProperties(),
                new Object[]{intakeTools}, collector, AiTraceLogger.disabled());
        BoundedToolCallingManager calls = mock(BoundedToolCallingManager.class);
        when(calls.calledToolNames()).thenReturn(Set.of("analyze_wardrobe_photo"));
        setGatewayField(gateway, "toolCallingManager", calls);

        String result = invokeAutoWardrobeIntake(gateway, userId, "加入衣柜", "好的，正在为你提取这套穿搭～");

        assertEquals("好的，正在为你提取这套穿搭～", result);
        verify(ingestion, never()).submitPhotoAnalysis(any(), any(), any());
        collector.finish();
    }

    @Test
    void replacesReferenceOutfitRefusalWithRealAnalysisWhenModelMistakesUserPhoto() throws Exception {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        String userId = "managed:11111111-1111-1111-1111-111111111111:wechat-user";
        collector.begin(userId);
        FashionWardrobeIntakeTools intakeTools = new FashionWardrobeIntakeTools(ingestion, collector, AiTraceLogger.disabled());
        when(ingestion.candidatesForPhoto(eq(userId), eq("img_recent"), isNull())).thenReturn(List.of());
        when(ingestion.submitPhotoAnalysis(eq(userId), eq("img_recent"), isNull())).thenReturn(true);

        SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                mock(org.springframework.ai.chat.model.ChatModel.class), new AiProperties(),
                new Object[]{intakeTools}, collector, AiTraceLogger.disabled());
        BoundedToolCallingManager calls = mock(BoundedToolCallingManager.class);
        when(calls.calledToolNames()).thenReturn(Set.of());
        LocalImageAssetStore store = mock(LocalImageAssetStore.class);
        when(store.recent(eq(userId), eq(1))).thenReturn(List.of(new LocalImageAssetStore.StoredImage(
                "img_recent", 1, null, "", "", Instant.now(), "image/jpeg", "uploaded", "")));
        ToolRegistry registry = mock(ToolRegistry.class);
        Method method = FashionWardrobeIntakeTools.class.getMethod("analyzeWardrobePhoto", String.class, Integer.class);
        when(registry.find("analyze_wardrobe_photo"))
                .thenReturn(Optional.of(new ToolRegistry.ToolEntry("analyze_wardrobe_photo", "", intakeTools, method)));
        setGatewayField(gateway, "toolCallingManager", calls);
        setGatewayField(gateway, "imageStore", store);
        setGatewayField(gateway, "toolRegistry", registry);

        String refusal = "这套是参考款，没办法直接加入您的个人衣橱哦～";
        String result = invokeAutoWardrobeIntake(gateway, userId, "帮我加入衣柜吧", refusal);

        assertFalse(result.contains("参考款"));
        assertTrue(result.contains("正在识别图片中"));
        verify(ingestion).submitPhotoAnalysis(userId, "img_recent", null);
        collector.finish();
    }

    @Test
    void autoSubmitsCutoutWhenThePhotoAlreadyHasCandidates() throws Exception {
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        String userId = "managed:11111111-1111-1111-1111-111111111111:wechat-user";
        collector.begin(userId);
        FashionWardrobeIntakeTools intakeTools = new FashionWardrobeIntakeTools(ingestion, collector, AiTraceLogger.disabled());
        when(ingestion.candidatesForPhoto(eq(userId), eq("img_recent"), isNull()))
                .thenReturn(List.of(pendingCandidate("cand-1")));
        when(ingestion.selectCandidatesForCutout(eq(userId), eq(List.of("cand-1"))))
                .thenReturn(List.of(new com.example.ykdsummer.fashion.domain.GarmentCutoutTask(
                        "task-1", "cand-1", 7L, "instance", 11L, 1, "",
                        com.example.ykdsummer.fashion.domain.GarmentCutoutTaskStatus.PENDING,
                        null, "", null, null, Instant.now().plusSeconds(600), Instant.now(), Instant.now())));

        SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                mock(org.springframework.ai.chat.model.ChatModel.class), new AiProperties(),
                new Object[]{intakeTools}, collector, AiTraceLogger.disabled());
        BoundedToolCallingManager calls = mock(BoundedToolCallingManager.class);
        when(calls.calledToolNames()).thenReturn(Set.of());
        LocalImageAssetStore store = mock(LocalImageAssetStore.class);
        when(store.recent(eq(userId), eq(1))).thenReturn(List.of(new LocalImageAssetStore.StoredImage(
                "img_recent", 1, null, "", "", Instant.now(), "image/jpeg", "uploaded", "")));
        ToolRegistry registry = mock(ToolRegistry.class);
        Method submitMethod = FashionWardrobeIntakeTools.class
                .getMethod("submitGarmentCutout", List.class, String.class);
        when(registry.find("submit_garment_cutout"))
                .thenReturn(Optional.of(new ToolRegistry.ToolEntry("submit_garment_cutout", "", intakeTools, submitMethod)));
        setGatewayField(gateway, "toolCallingManager", calls);
        setGatewayField(gateway, "imageStore", store);
        setGatewayField(gateway, "toolRegistry", registry);
        setGatewayField(gateway, "wardrobeIngestion", ingestion);

        String result = invokeAutoWardrobeIntake(gateway, userId, "确认", "好的～");

        assertTrue(result.contains("已提交"), result);
        assertFalse(result.contains("内部候选"));
        assertFalse(result.contains("candidateId"));
        verify(ingestion).selectCandidatesForCutout(userId, List.of("cand-1"));
        collector.finish();
    }

    @Test
    void stripsInternalCandidateListFromFallbackWhenAnalysisReturnsExistingCandidates() throws Exception {
        // wardrobeIngestion 未注入（hasCandidates=false 走识别分支），但 analyze 工具内部
        // 查到照片已有候选并返回"内部候选"列表 → 兜底必须剥离，防止编号泄露给用户。
        FashionWardrobeIngestionService ingestion = mock(FashionWardrobeIngestionService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        String userId = "managed:11111111-1111-1111-1111-111111111111:wechat-user";
        collector.begin(userId);
        FashionWardrobeIntakeTools intakeTools = new FashionWardrobeIntakeTools(ingestion, collector, AiTraceLogger.disabled());
        when(ingestion.candidatesForPhoto(eq(userId), eq("img_recent"), isNull()))
                .thenReturn(List.of(pendingCandidate("cand-1")));

        SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                mock(org.springframework.ai.chat.model.ChatModel.class), new AiProperties(),
                new Object[]{intakeTools}, collector, AiTraceLogger.disabled());
        BoundedToolCallingManager calls = mock(BoundedToolCallingManager.class);
        when(calls.calledToolNames()).thenReturn(Set.of());
        LocalImageAssetStore store = mock(LocalImageAssetStore.class);
        when(store.recent(eq(userId), eq(1))).thenReturn(List.of(new LocalImageAssetStore.StoredImage(
                "img_recent", 1, null, "", "", Instant.now(), "image/jpeg", "uploaded", "")));
        ToolRegistry registry = mock(ToolRegistry.class);
        Method analyzeMethod = FashionWardrobeIntakeTools.class
                .getMethod("analyzeWardrobePhoto", String.class, Integer.class);
        when(registry.find("analyze_wardrobe_photo"))
                .thenReturn(Optional.of(new ToolRegistry.ToolEntry("analyze_wardrobe_photo", "", intakeTools, analyzeMethod)));
        setGatewayField(gateway, "toolCallingManager", calls);
        setGatewayField(gateway, "imageStore", store);
        setGatewayField(gateway, "toolRegistry", registry);

        String result = invokeAutoWardrobeIntake(gateway, userId, "加入衣柜", "好的～");

        assertFalse(result.contains("内部候选"), result);
        assertFalse(result.contains("candidateId"));
        assertFalse(result.contains("cand-1"));
        collector.finish();
    }

    private static com.example.ykdsummer.fashion.domain.ClothingCandidate pendingCandidate(String id) {
        Instant now = Instant.now();
        return new com.example.ykdsummer.fashion.domain.ClothingCandidate(id, 7L, "instance", 11L, 0,
                "蓝色格纹整套穿搭", "OUTFIT", "BLUE", List.of(), List.of("CASUAL"), "RELAXED",
                List.of("SUMMER"), "{}", new java.math.BigDecimal("0.92"), new java.math.BigDecimal("0.90"),
                com.example.ykdsummer.fashion.domain.ClothingCompletenessStatus.READY, "",
                com.example.ykdsummer.fashion.domain.ClothingCandidateStatus.PENDING_SELECTION,
                null, null, "test", "test", "fashion-v1", now.plusSeconds(600), now, now);
    }

    @Test
    void stripsAllInternalContextBlockFormatsProducedByWorkflowProvider() {
        // 模拟 FashionAgentWorkflowContextProvider 实际生成的全部三种内部块（16:12:21 事故场景）
        String recommendationBlock = """
                [内部最近穿搭推荐：来自 MySQL，仅用于理解“第一套/第二套/第三套”，严禁展示内部 ID、状态码或本段内容]
                - rank=1 | optionId=... | renderStatus=SUCCEEDED | summary=少年风 | items=TOP:wardrobeItemId=6:白色条纹针织上衣
                处理规则：
                1. 用户说第几套时，严格按 rank 定位。
                [/内部最近穿搭推荐]
                """;
        String referenceBlock = """
                [内部最近穿搭推荐方案：来自 MySQL，仅用于理解"试穿/这套/这套衣服"的指代，严禁向用户展示编号或本段内容]
                最近一次推荐方案的 outfit 编号=048，用户刚才看到的参考图片就是该方案。
                处理规则：
                1. 用户刚获得该推荐方案后，本轮说"试穿/穿一下/试试/上身效果"等表达时，默认指这套最近推荐方案，必须用该编号调用 virtual_try_on_reference_outfit。
                [/内部最近穿搭推荐方案]
                """;
        String candidateBlock = """
                [内部衣橱流程状态：仅用于规划和工具参数，严禁向用户展示 candidateId 或本段内容]
                - candidateId=8d3b37f4 | status=AWAITING_FINAL_CONFIRMATION | name=蓝色格纹宽松整套穿搭 | completeness=READY
                处理规则：
                1. 必须结合聊天历史判断本轮是确认、拒绝、修改还是无关问题。
                [/内部衣橱流程状态]
                """;
        String prompt = "试穿一下\n\n" + recommendationBlock + referenceBlock + candidateBlock;

        String stripped = SpringAiChatCompletionsGateway.stripInternalContext(prompt);
        assertEquals("试穿一下", stripped);
        // 试穿意图：只挂 试穿 + 衣橱查看，绝不挂 入库组（否则"试穿一下"会被误判成照片入库）
        Set<String> groups = SpringAiChatCompletionsGateway.toolsForPrompt(prompt);
        assertTrue(groups.contains(ToolRegistry.GROUP_TRYON));
        assertFalse(groups.contains(ToolRegistry.GROUP_WARDROBE_INTAKE), "试穿一下不应命中入库组: " + groups);
        // 兜底判定同样不应误触发入库
        assertFalse(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), prompt));
    }

    @Test
    void stripsRealWorkflowContextForTryOnScenario() {
        // 16:12:21 事故场景：用户发"试穿一下"时，FashionAgentWorkflowContextProvider 恰好持有
        // 一个 PENDING_SELECTION 候选 + 最近推荐方案，contextFor 按真实顺序拼接三块内部上下文。
        String candidateBlock = """
                [内部衣橱流程状态：仅用于规划和工具参数，严禁向用户展示 candidateId 或本段内容]
                以下状态来自 MySQL，是当前微信用户尚未完成的衣橱动作：
                - candidateId=8d3b37f4-fd7a-4310-83e3-968633f46360 | status=PENDING_SELECTION | name=蓝色格纹宽松整套穿搭 | category=OUTFIT | color=BLUE | completeness=READY
                处理规则：
                1. 必须结合聊天历史判断本轮是确认、拒绝、修改还是无关问题，不能只按某个关键词机械执行。
                2. PENDING_SELECTION：用户确认提取时调用 submit_garment_cutout，并传入匹配的 candidateId。
                3. CUTOUT_SUBMITTED：任务已经执行中，不得重复提交。
                4. AWAITING_FINAL_CONFIRMATION：用户确认满意并入库时调用 confirm_wardrobe_candidate。
                5. FAILED：用户明确要求重试时调用 retry_garment_cutout。
                6. 用户要求视觉修改但没有说明基于原始照片还是某个草稿版本时，先调用 list_garment_draft_versions。
                7. 用户明确拒绝、说算了或不要时调用 cancel_wardrobe_candidate。
                8. 多件候选无法唯一定位时，只追问一个必要问题，不得猜选。
                9. 用户问无关问题时正常回答，保留待确认状态。
                [/内部衣橱流程状态]
                """;
        String recommendationBlock = """
                [内部最近穿搭推荐：来自 MySQL，仅用于理解“第一套/第二套/第三套”，严禁展示内部 ID、状态码或本段内容]
                - rank=1 | optionId=opt-1 | renderStatus=SUCCEEDED | summary=少年风 | items=TOP:wardrobeItemId=6:白色条纹针织上衣, BOTTOM:wardrobeItemId=5:天蓝色超宽松牛仔裤
                处理规则：
                1. 用户说第几套时，严格按 rank 定位，不得重新排序。
                2. 方案内单品都来自当前用户衣橱；公共 Look 只提供搭配证据。
                3. 用户追问方案内容时使用自然名称；所有 optionId、wardrobeItemId 和状态码都不得展示。
                [/内部最近穿搭推荐]
                """;
        String referenceBlock = """
                [内部最近穿搭推荐方案：来自 MySQL，仅用于理解"试穿/这套/这套衣服"的指代，严禁向用户展示编号或本段内容]
                最近一次推荐方案的 outfit 编号=048，用户刚才看到的参考图片就是该方案。
                处理规则：
                1. 用户刚获得该推荐方案后，本轮说"试穿/穿一下/试试/上身效果"等表达且未明确指向衣橱单品（未提"衣柜/衣橱里的"）时，
                   默认指这套最近推荐方案，必须用该编号调用 virtual_try_on_reference_outfit，不得只口头承诺试穿。
                2. 若用户最近一步操作是加入/预览衣橱单品（见[内部衣橱流程状态]），说"试穿一下/穿一下/试试"等未指明出处的表达，默认指刚处理的衣橱单品，
                   必须调用 virtual_try_on_wardrobe_item，绝不调用本工具，也不要从历史对话中自行挑选其他 outfit 编号。
                3. 不得为了试穿重新调用 fashion_consultant（那会生成一套新方案并发来新图片）；只有从未推荐过任何方案时才调用 fashion_consultant 获取。
                [/内部最近穿搭推荐方案]
                """;
        // contextFor 实际拼接顺序：候选块 → 推荐块 → 引用方案块
        String prompt = "试穿一下" + candidateBlock + recommendationBlock + referenceBlock;

        // 剥离后只剩用户消息
        String stripped = SpringAiChatCompletionsGateway.stripInternalContext(prompt);
        assertEquals("试穿一下", stripped);
        // 路由：只挂 试穿 + 衣橱查看，绝不挂 入库组
        Set<String> groups = SpringAiChatCompletionsGateway.toolsForPrompt(prompt);
        assertTrue(groups.contains(ToolRegistry.GROUP_TRYON));
        assertFalse(groups.contains(ToolRegistry.GROUP_WARDROBE_INTAKE), "真实上下文下不应命中入库组: " + groups);
        // 入库兜底不应误触发
        assertFalse(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), prompt));
        // 试穿兜底应触发：hasTryOnIntent 不能因为内部块含"衣橱/衣柜"被 TRY_ON_EXCLUDE 挡掉
        assertTrue(SpringAiChatCompletionsGateway.hasTryOnIntent(stripped));
    }

    @Test
    void intakeFallbackSkipsPureTryOnEvenWhenStripLeaksInternalWords() {
        // 防御场景：内部块剥离异常（如未闭合），残留"确认/照片/衣橱"等词，但用户本意是试穿。
        // shouldAutoWardrobeIntake 必须仍然返回 false，绝不让"试穿一下"走照片入库链路。
        String leakedPrompt = "试穿一下\n[内部衣橱流程状态：用户确认提取时调用 submit_garment_cutout，"
                + "基于原始照片还是某个草稿版本时先调用 list_garment_draft_versions（该块未闭合";
        // 剥离正则会因未闭合而无法移除该块（无 [/内部...]），残留文本直接命中入库正则
        assertTrue(SpringAiChatCompletionsGateway.stripInternalContext(leakedPrompt)
                .contains("确认"));
        assertFalse(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), leakedPrompt),
                "纯试穿意图即使内部块剥离失败也绝不能触发入库兜底");
    }

    @Test
    void intakeFallbackStillTriggersForCandidateConfirmAndIntake() {
        // 8.22/8.24 修复不能被破坏：候选确认/入库意图仍要触发入库兜底
        assertTrue(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "确认"));
        assertTrue(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "就要牛仔裤"));
        assertTrue(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "选第一件"));
        assertTrue(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "把这张照片放进我的衣橱"));
        assertTrue(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "抠图"));
        assertTrue(SpringAiChatCompletionsGateway.shouldAutoWardrobeIntake(Set.of(), "就这件吧"));
    }

    @Test
    void documentInstructionWrapperMustNotPolluteIntentRouting() {
        // 16:42:45 事故复现：ILinkReplyService 把纯文本消息包装成文档指令 prompt，
        // 包装文本中的固定规则文字被误命中入库意图 → 路由挂 intake 组 → 模型被误导。
        // 文本与 FileInstructionService.DOCUMENT_TOOL_INSTRUCTION 保持一致（包私有，测试内联）。
        String wrapped = """
                ## 当前文档与工具规则
                用户可能在讨论、创建或修改文档。不要输出 FILE_GEN、JSON 标记或伪造文件链接。
                需要新建常规 Word、Excel、PDF 或 TXT 文档时调用 create_document；用户明确指定文件名，
                或需要 PPT、Markdown、HTML、CSV、JSON、XML 等独立附件时调用 produce_file；需要修改、润色当前或指定文档时，先调用
                get_current_document 读取 assetId、版本和正文，再调用 replace_document_content 并提供完整新正文。
                用户只要求“转成 PDF / Word / Excel / TXT”而不改变正文时，必须先查询后调用
                convert_document_format；未得到工具成功结果前，不得声称已经生成或发送文件。
                用户明确说“回到/恢复/撤销到第 N 版”时，先查询版本后调用 restore_document_version。
                工具会保存不可覆盖的版本并把真实文件发送给用户。若没有当前文档且用户没有请求新建，简短追问。
                用户本轮请求：推荐一套少年风穿搭
                """;
        assertTrue(wrapped.contains("当前文档"));
        // 路由必须只看真实用户意图：文档指令模板（含"用户只要求"）不得命中入库组
        Set<String> groups = SpringAiChatCompletionsGateway.toolsForPrompt(wrapped);
        assertFalse(groups.contains(ToolRegistry.GROUP_WARDROBE_INTAKE),
                "文档指令包装文本不应命中入库组: " + groups);
        // 真实候选选择表达不受影响
        assertTrue(SpringAiChatCompletionsGateway.toolsForPrompt("只要牛仔裤")
                .contains(ToolRegistry.GROUP_WARDROBE_INTAKE));
    }

    private static void setGatewayField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = SpringAiChatCompletionsGateway.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String invokeAutoWardrobeIntake(SpringAiChatCompletionsGateway gateway, String userId,
                                                   String prompt, String text) throws Exception {
        java.lang.reflect.Method method = SpringAiChatCompletionsGateway.class
                .getDeclaredMethod("maybeAutoWardrobeIntake", String.class, String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(gateway, userId, prompt, text);
    }
}
