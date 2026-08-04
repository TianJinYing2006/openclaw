package com.example.ykdsummer.ai.fashion;

import com.example.ykdsummer.ai.fashion.agent.AgentCoordinator;
import com.example.ykdsummer.ai.fashion.model.FashionConversation;
import com.example.ykdsummer.ai.fashion.model.FashionRequest;
import com.example.ykdsummer.ai.fashion.model.FashionResult;
import com.example.ykdsummer.ai.fashion.model.FeedbackDetection;
import com.example.ykdsummer.ai.fashion.profile.FashionConversationService;
import com.example.ykdsummer.ai.fashion.rag.QueryAnalyzer;
import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import com.example.ykdsummer.ai.orchestration.AgentTool;
import com.example.ykdsummer.ai.tool.ImageTaskCompletionEvent;
import com.example.ykdsummer.ai.tool.ImageTaskCompletionPublisher;
import com.example.ykdsummer.ai.tool.ImageTaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 穿搭推荐服务（对外入口）。
 *
 * <p>注册为 @Tool，由通用 LLM 在检测到穿搭类请求时自动调用。
 * 内部委托给 AgentCoordinator 执行多 Agent 协作管道。
 */
@Component
public class FashionAgentService {

    private static final Logger log = LoggerFactory.getLogger(FashionAgentService.class);

    /** OSS 参考穿搭图片 URL（fashion-reference/outfits 前缀下的 png/webp 等）。 */
    private static final Pattern REFERENCE_IMAGE_URL = Pattern.compile(
            "https?://[^\\s()（）]+/fashion-reference/outfits/[^\\s()（）]+\\.(?:png|webp|jpe?g|gif)");

    /** RAG 上下文中的 outfit 编号标记，如 [outfit_002]。 */
    private static final Pattern OUTFIT_ID_MARKER = Pattern.compile("\\[outfit_(\\d+)\\]");

    /** 单次回复最多补发的参考图片数，避免刷屏。 */
    private static final int MAX_REFERENCE_IMAGES = 4;

    /**
     * 同一用户同一参考穿搭图（outfit 编号）在该窗口期内不重复补发。
     * <p>用户说"试穿一下"等场景会触发 LLM 二次调用 fashion_consult，导致同一 outfit
     * 的参考图被发布两遍；这里按 userId+outfitId 去重，避免刷屏。
     * <p>可通过 {@code app.fashion.reference-image.dedup-window} 配置，默认 10 分钟。
     */
    private static final long DEFAULT_REFERENCE_IMAGE_DEDUP_WINDOW_MILLIS = 10 * 60 * 1000L;

    /** key = userId + ":" + outfitId，value = 最近一次补发时间戳。 */
    private final ConcurrentHashMap<String, Long> referenceImageSentAt = new ConcurrentHashMap<>();

    /** 去重窗口（毫秒），从配置读取，默认 10 分钟。 */
    private long referenceImageDedupWindowMillis = DEFAULT_REFERENCE_IMAGE_DEDUP_WINDOW_MILLIS;

    @org.springframework.beans.factory.annotation.Value("${app.fashion.reference-image.dedup-window:10m}")
    public void setReferenceImageDedupWindow(java.time.Duration dedupWindow) {
        this.referenceImageDedupWindowMillis = dedupWindow.toMillis();
    }

    private final AgentCoordinator coordinator;
    private final FashionResponseFormatter formatter;
    private final FashionConversationService conversationService;
    private final QueryAnalyzer queryAnalyzer;
    private final ReferenceImageResolver imageResolver;
    private final ImageTaskRunner imageTaskRunner;
    private final ImageTaskCompletionPublisher completionPublisher;
    private final RestClient httpClient;

    public FashionAgentService(AgentCoordinator coordinator,
                               FashionResponseFormatter formatter,
                               FashionConversationService conversationService,
                               QueryAnalyzer queryAnalyzer,
                               ReferenceImageResolver imageResolver,
                               ImageTaskRunner imageTaskRunner,
                               ImageTaskCompletionPublisher completionPublisher) {
        this.coordinator = coordinator;
        this.formatter = formatter;
        this.conversationService = conversationService;
        this.queryAnalyzer = queryAnalyzer;
        this.imageResolver = imageResolver;
        this.imageTaskRunner = imageTaskRunner;
        this.completionPublisher = completionPublisher;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.httpClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 穿搭推荐入口。
     *
     * <p>用户输入如 "今天我要去海边，帮我推荐一套穿搭"，
     * 返回经过多 Agent 协作分析的穿搭方案文案。
     *
     * @param userInput 用户的穿搭需求描述
     * @return 格式化的穿搭推荐文案
     */
    @AgentTool
    @Tool(name = "fashion_consultant",
          description = "AI穿搭推荐入口，返回带参考图片的完整穿搭方案。用户询问穿什么、怎么搭、帮我配一身、衣服搭配建议、场合着装、" +
                        "海边/婚礼/通勤/约会/旅行/面试，或根据天气/季节/温度推荐穿搭时，必须调用此工具，不得只输出穿搭文字。" +
                        "输入保留用户原话，工具会完成需求分析、穿搭知识检索、多Agent评审并返回适合微信阅读的最终文案。" +
                        "注意：用户说'试穿/试试这套/穿上看看'时不要调用此工具：若针对衣橱单品（如刚加入衣橱）调用 virtual_try_on_wardrobe_item，" +
                        "若明确引用刚获得的推荐方案调用 virtual_try_on_reference_outfit。")
    public String consult(
            @ToolParam(description = "用户的穿搭需求描述") String userInput
    ) {
        if (userInput == null || userInput.isBlank()) {
            return "你可以告诉我今天的场景，比如通勤、约会、海边、婚礼或面试，我来给你搭一套。";
        }
        String userId;
        try {
            userId = AgentSessionContext.requireUserId();
        } catch (IllegalStateException e) {
            log.error("Fashion consult called without session context", e);
            return "抱歉，当前会话上下文未初始化，无法处理穿搭请求，请稍后再试。";
        }
        log.info("Fashion consult request from user {}: {}", userId, userInput);

        // 反馈检测：轻量 LLM 分类判断是否为对上次推荐的反馈，命中时回填到用户画像数据源
        recordFeedbackIfAny(userId, userInput);

        try {
            FashionRequest request = new FashionRequest(userId, userInput);
            FashionResult result = coordinator.process(request);
            scheduleReferenceImages(userId, result);
            return formatter.format(result);

        } catch (Exception e) {
            log.error("Fashion pipeline unexpected error: {}", e.getMessage(), e);
            return "抱歉，穿搭推荐服务暂时遇到了问题，请稍后再试。";
        }
    }

    /**
     * 把参考穿搭图片异步下载并补发给用户。
     *
     * <p>文案先返回，图片在后台通过 {@link ImageTaskRunner} 下载，完成后以
     * {@link ImageTaskCompletionEvent} 交给消息渠道补发，避免图片下载阻塞文案延迟。
     * 优先按 RAG 上下文里的 {@code [outfit_XXX]} 标记查 URL 映射表（不依赖 RAGFlow
     * 分块内容是否保留链接）；查不到时回退为直接从上下文抓取
     * {@code fashion-reference/outfits/...} URL。下载失败只记日志，不影响文案返回。
     */
    private void scheduleReferenceImages(String userId, FashionResult result) {
        if (imageTaskRunner == null || completionPublisher == null
                || result == null || result.ragContext() == null) {
            log.info("scheduleReferenceImages skipped: runner={} publisher={} resultNull={} ragNull={}",
                    imageTaskRunner != null, completionPublisher != null,
                    result == null, result == null || result.ragContext() == null);
            return;
        }
        String firstChunk = firstChunkText(result.ragContext());
        log.info("scheduleReferenceImages: ragLen={} firstChunkHead={}",
                result.ragContext().length(),
                firstChunk.length() > 80 ? firstChunk.substring(0, 80) : firstChunk);

        ResolvedReference resolved = resolveReferenceImages(result, firstChunk);
        // 同一用户同一 outfit 在窗口期内已补发过则跳过，避免"试穿"等二次 consult 重复刷图
        if (resolved.outfitId() != null && shouldSkipReferenceImages(userId, resolved.outfitId())) {
            log.info("scheduleReferenceImages: skip {} (dedup window, last sent recently)", resolved.outfitId());
            return;
        }
        log.info("scheduleReferenceImages: resolved {} urls", resolved.urls().size());
        int accepted = 0;
        for (String url : resolved.urls()) {
            if (accepted >= MAX_REFERENCE_IMAGES) {
                break;
            }
            boolean submitted = imageTaskRunner.submit(() -> downloadAndSendReferenceImage(userId, url));
            if (submitted) {
                accepted++;
            } else {
                log.warn("Reference image task rejected (queue full): {}", url);
            }
        }
    }

    /**
     * 去重判断：同一 userId+outfitId 在窗口期内已补发过则跳过（返回 true），
     * 否则记录本次补发时间戳并放行。
     * <p>并发下先检查后写入，少量竞态只影响是否多补发一次，可接受。
     * 当 Map 超过 200 条时顺带清理过期条目，防止长期运行内存泄漏。
     */
    private boolean shouldSkipReferenceImages(String userId, String outfitId) {
        String key = userId + ":" + outfitId;
        long now = System.currentTimeMillis();
        // 低频清理：Map 超过 200 条时移除已过期条目，防止无限增长
        if (referenceImageSentAt.size() > 200) {
            referenceImageSentAt.entrySet().removeIf(
                    e -> now - e.getValue() > referenceImageDedupWindowMillis);
        }
        Long lastSent = referenceImageSentAt.get(key);
        if (lastSent != null && now - lastSent < referenceImageDedupWindowMillis) {
            log.info("scheduleReferenceImages: dedup hit for outfit {} (last sent {}ms ago)",
                    outfitId, now - lastSent);
            return true;
        }
        referenceImageSentAt.put(key, now);
        return false;
    }

    /** 参考图解析结果：outfitId 可能为 null（走 URL 兜底时没有编号，不参与去重）。 */
    private record ResolvedReference(String outfitId, List<String> urls) {}

    private ResolvedReference resolveReferenceImages(FashionResult result, String firstChunk) {
        if (imageResolver != null) {
            // 1. 优先用 Coordinator 最终方案引用的 outfit 编号，确保图文对齐
            String coordinatorOutfitId = extractOutfitIdFromCoordinator(result);
            if (coordinatorOutfitId != null) {
                List<String> urls = imageResolver.urlsFor(coordinatorOutfitId);
                if (!urls.isEmpty()) {
                    log.info("scheduleReferenceImages: coordinatorOutfitId={} -> {} urls",
                            coordinatorOutfitId, urls.size());
                    return new ResolvedReference(coordinatorOutfitId, urls);
                }
                log.warn("scheduleReferenceImages: coordinatorOutfitId={} not in image map, trying RAG fallback",
                        coordinatorOutfitId);
            }
            // 2. Coordinator outfitId 无效（LLM 可能生成不存在的编号如 "001"）时，
            //    回退到 RAG 上下文第一个 chunk 的 [outfit_XXX] 标记
            String ragOutfitId = extractOutfitId(firstChunk);
            if (ragOutfitId != null) {
                List<String> urls = imageResolver.urlsFor(ragOutfitId);
                if (!urls.isEmpty()) {
                    log.info("scheduleReferenceImages: ragOutfitId={} -> {} urls (coordinator fallback)",
                            ragOutfitId, urls.size());
                    return new ResolvedReference(ragOutfitId, urls);
                }
                log.warn("scheduleReferenceImages: ragOutfitId={} not in image map either", ragOutfitId);
            }
            log.info("scheduleReferenceImages: no outfitId resolved, trying direct URL extraction from RAG text");
        }
        // 3. 最终兜底：直接从 RAG 文本抓取图片 URL（无 outfitId，不参与去重）
        return new ResolvedReference(null, extractImageUrls(firstChunk));
    }

    /** 从 Coordinator 最终方案的 referenceOutfitId 提取 outfit 编号。 */
    private static String extractOutfitIdFromCoordinator(FashionResult result) {
        if (result == null || result.coordinator() == null
                || result.coordinator().refinedOutfit() == null) {
            return null;
        }
        String refId = result.coordinator().refinedOutfit().referenceOutfitId();
        if (refId == null || refId.isBlank()) {
            return null;
        }
        // 规范化：去掉前导零和 [outfit_] 标记，保持与 image_urls.json 的 key 一致
        return normalizeOutfitId(refId);
    }

    /** 将 "002"/"outfit_002"/"[outfit_002]" 统一为 "002"（与 image_urls.json 的 key 格式对齐）。 */
    private static String normalizeOutfitId(String raw) {
        String cleaned = raw.replace("[outfit_", "").replace("outfit_", "").replace("]", "").trim();
        // 纯数字时补齐前导零到3位，与 image_urls.json 的 key 格式对齐
        try {
            int num = Integer.parseInt(cleaned);
            return String.format("%03d", num);
        } catch (NumberFormatException e) {
            return cleaned;
        }
    }

    /** 后台下载参考图片，并通过完成事件补发给对应微信用户。 */
    private void downloadAndSendReferenceImage(String userId, String url) {
        try {
            byte[] bytes = httpClient.get().uri(url).retrieve().body(byte[].class);
            if (bytes != null && bytes.length > 0) {
                completionPublisher.publish(new ImageTaskCompletionEvent(
                        userId, "ref_" + UUID.randomUUID(), bytes, "", 0));
                log.info("Published reference outfit image: {}", url);
            }
        } catch (Exception e) {
            log.warn("Failed to download reference outfit image {}: {}", url, e.getMessage());
        }
    }

    /** 取 RAG 上下文第一个知识片段（"1. " 到 "\n\n2. " 之间）。 */
    private String firstChunkText(String ragContext) {
        int start = ragContext.indexOf("1. ");
        String head = start >= 0 ? ragContext.substring(start) : ragContext;
        int next = head.indexOf("\n\n2. ");
        return next > 0 ? head.substring(0, next) : head;
    }

    /** 从片段文本提取 [outfit_XXX] 编号；无标记返回 null。 */
    private String extractOutfitId(String text) {
        Matcher matcher = OUTFIT_ID_MARKER.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 从片段文本直接抓取参考图片 URL（限制在 MAX_REFERENCE_IMAGES 张）。 */
    private List<String> extractImageUrls(String text) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = REFERENCE_IMAGE_URL.matcher(text);
        while (matcher.find() && urls.size() < MAX_REFERENCE_IMAGES) {
            urls.add(matcher.group());
        }
        return urls;
    }

    /**
     * 检测用户输入是否为对上一次推荐的反馈，并回填到对话记录。
     *
     * <p>由 {@link QueryAnalyzer#detectFeedback} 做轻量 LLM 分类（few-shot），
     * 识别是否反馈及情感倾向；命中时将输入连同情感前缀写入该用户最近一条穿搭对话的
     * user_feedback 字段，供后续用户画像检索使用。检测失败不影响主流程。
     */
    private void recordFeedbackIfAny(String userId, String userInput) {
        if (conversationService == null || queryAnalyzer == null) return;
        FeedbackDetection detection = queryAnalyzer.detectFeedback(userInput);
        if (!detection.isFeedback()) return;
        try {
            FashionConversation latest = conversationService.findLatest(userId);
            if (latest != null) {
                String feedback = "【" + detection.sentiment() + "】" + userInput;
                conversationService.updateFeedback(latest.id(), feedback);
                log.info("Recorded fashion feedback for conversation {}: {}", latest.id(), feedback);
            }
        } catch (Exception e) {
            log.debug("Failed to record fashion feedback: {}", e.getMessage());
        }
    }
}
