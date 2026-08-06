package com.example.ykdsummer.ai.fashion;

import com.example.ykdsummer.ai.fashion.agent.AgentCoordinator;
import com.example.ykdsummer.ai.fashion.model.FashionRequest;
import com.example.ykdsummer.ai.fashion.model.FashionResult;
import com.example.ykdsummer.ai.fashion.profile.FashionConversationService;
import com.example.ykdsummer.ai.fashion.profile.PreferenceInferenceService;
import com.example.ykdsummer.ai.fashion.rag.QueryAnalyzer;
import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import com.example.ykdsummer.ai.orchestration.AgentTool;
import com.example.ykdsummer.ai.tool.ImageTaskCompletionEvent;
import com.example.ykdsummer.ai.tool.ImageTaskCompletionPublisher;
import com.example.ykdsummer.ai.tool.ImageTaskRunner;
import com.example.ykdsummer.fashion.application.FashionVisualPreviewService;
import com.example.ykdsummer.fashion.application.FashionVisualPreviewService.WardrobePreview;
import com.example.ykdsummer.fashion.domain.FashionAttributeNormalizer;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

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
    /** 反馈检测已由主路径 {@link FashionFeedbackRecorder} 覆盖，此字段仅保留以兼容既有构造签名与测试。 */
    private final FashionConversationService conversationService;
    private final QueryAnalyzer queryAnalyzer;
    private final ReferenceImageResolver imageResolver;
    /** 参考图补发已改为同步发送，此字段仅保留以兼容既有构造签名与测试。 */
    private final ImageTaskRunner imageTaskRunner;
    private final ImageTaskCompletionPublisher completionPublisher;
    private final ReferenceImageSendGate imageSendGate;
    private final RestClient httpClient;
    private volatile PreferenceInferenceService preferenceInference;
    /** 衣橱单品图片解析服务（读取用户衣橱单品图）；未装配（如单测或持久化关闭）时跳过衣橱图发送。 */
    private volatile FashionVisualPreviewService visualPreviews;
    /** 参考图异步发送池（虚拟线程）；未装配（如单测）时降级为串行发送。 */
    private volatile ExecutorService executor;

    /** 参考图下载器；默认走 {@link #httpClient} 直连，测试可注入替身。 */
    public interface ReferenceImageDownloader {
        byte[] download(String url) throws java.io.IOException;
    }

    private ReferenceImageDownloader downloader;

    public FashionAgentService(AgentCoordinator coordinator,
                               FashionResponseFormatter formatter,
                               FashionConversationService conversationService,
                               QueryAnalyzer queryAnalyzer,
                               ReferenceImageResolver imageResolver,
                               ImageTaskRunner imageTaskRunner,
                               ImageTaskCompletionPublisher completionPublisher,
                               ReferenceImageSendGate imageSendGate) {
        this.coordinator = coordinator;
        this.formatter = formatter;
        this.conversationService = conversationService;
        this.queryAnalyzer = queryAnalyzer;
        this.imageResolver = imageResolver;
        this.imageTaskRunner = imageTaskRunner;
        this.completionPublisher = completionPublisher;
        this.imageSendGate = imageSendGate;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.httpClient = RestClient.builder().requestFactory(factory).build();
        this.downloader = url -> httpClient.get().uri(url).retrieve().body(byte[].class);
    }

    /** 偏好推断（反馈 → 规范化偏好画像）由 Spring 注入；测试等直接 new 场景可缺省。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPreferenceInference(PreferenceInferenceService preferenceInference) {
        this.preferenceInference = preferenceInference;
    }

    /** 衣橱单品图片服务由 Spring 注入（持久化关闭时不存在，走参考图兜底）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setVisualPreviews(FashionVisualPreviewService visualPreviews) {
        this.visualPreviews = visualPreviews;
    }

    /** 覆盖默认下载器（默认走 httpClient 直连），便于测试注入替身。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReferenceImageDownloader(ReferenceImageDownloader downloader) {
        if (downloader != null) {
            this.downloader = downloader;
        }
    }

    /** 复用穿搭 Agent 的虚拟线程池，并行下载并发送参考图；参考图彼此独立，串行会白白吃掉总延迟。 */
    @Autowired(required = false)
    public void setExecutor(@Qualifier("fashionAgentParallelExecutor") ExecutorService executor) {
        this.executor = executor;
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
                        "注意：当搭配基于用户衣橱单品（如'我的红色条纹T恤''衣柜里那件条纹衫'）时，userInput 必须完整保留单品名称" +
                        "（示例：'用红色条纹T恤搭配一套'），系统会自动展示该衣橱单品图片，此时不得编造 RAG 参考穿搭编号。" +
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

        try {
            FashionRequest request = new FashionRequest(userId, userInput);
            FashionResult result = coordinator.process(request);
            // 基于衣橱单品的搭配：优先发衣橱单品自己的图片（图文一致）；未匹配到衣橱单品时回退 RAG 参考图
            if (!scheduleWardrobeItemImage(userId, userInput)) {
                scheduleReferenceImages(userId, result);
            }
            return formatter.format(result);

        } catch (Exception e) {
            log.error("Fashion pipeline unexpected error: {}", e.getMessage(), e);
            return "抱歉，穿搭推荐服务暂时遇到了问题，请稍后再试。";
        }
    }

    /**
     * 基于衣橱单品的搭配场景：从用户输入匹配衣橱单品，并发送该单品自己的图片。
     *
     * <p>返回 true 表示已识别为衣橱单品场景（匹配到单品并发送其图片，或窗口期内已发过），
     * 调用方应跳过 RAG 参考图，避免图文不符（参考图是公共 Look，与用户衣橱无关）。
     * 返回 false 表示未匹配到衣橱单品，走 RAG 参考图兜底。
     * <p>图片与参考图走同一 {@link ImageTaskCompletionEvent} 链路：先发图、后发文本，
     * 失败只记日志不阻断文案。
     */
    private boolean scheduleWardrobeItemImage(String userId, String userInput) {
        FashionVisualPreviewService previews = visualPreviews;
        if (previews == null || userInput == null || userInput.isBlank()) {
            return false;
        }
        try {
            Optional<WardrobePreview> best = bestMatchingWardrobeItem(previews, userId, userInput);
            if (best.isEmpty()) {
                return false;
            }
            WardrobePreview preview = best.get();
            if (shouldSkipWardrobeImage(userId, preview.item().id())) {
                log.info("scheduleWardrobeItemImage: skip itemId={} (dedup window)", preview.item().id());
                return true;
            }
            publishWardrobeItemImage(userId, preview);
            return true;
        } catch (RuntimeException e) {
            log.warn("scheduleWardrobeItemImage failed for user {}: {}", anonymize(userId), e.getMessage());
            return false;
        }
    }

    /** 从用户衣橱中挑选与输入最匹配且有图的一件单品；无匹配返回空。 */
    private Optional<WardrobePreview> bestMatchingWardrobeItem(
            FashionVisualPreviewService previews, String userId, String userInput) {
        List<WardrobePreview> candidates = previews.wardrobeItems(
                userId, WardrobeSearchCriteria.from(null, null, null, null, null, null, null, null), 20);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        WardrobePreview best = null;
        int bestScore = 0;
        for (WardrobePreview candidate : candidates) {
            if (!candidate.hasImage()) {
                continue;
            }
            int score = wardrobeMatchScore(candidate.item(), userInput);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best);
    }

    /** 衣橱单品与用户输入的匹配分：名称命中权重最高，颜色/图案次之；0 表示不匹配。 */
    private static int wardrobeMatchScore(WardrobeItem item, String userInput) {
        int score = 0;
        String name = safe(item.displayName());
        String lowerInput = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if (!name.isBlank() && lowerInput.contains(name.toLowerCase(Locale.ROOT))) {
            score += 3;
        }
        if (attributeMatches(item.colorPrimary(), lowerInput, userInput)) {
            score += 1;
        }
        for (String secondary : item.secondaryColors()) {
            if (attributeMatches(secondary, lowerInput, userInput)) {
                score += 1;
                break;
            }
        }
        if (attributeMatches(item.patternCode(), lowerInput, userInput)) {
            score += 1;
        }
        return score;
    }

    /**
     * 单品属性（颜色/图案等）是否被用户输入命中。属性可能是中文（"红色"/"条纹"）或
     * 英文编码（RED/STRIPES/T_SHIRT）：中文直接包含判断；编码则枚举用户输入的连续片段，
     * 经 {@link FashionAttributeNormalizer} token 化后与属性编码比对（"红色"→RED、"条纹"→STRIPED）。
     */
    private static boolean attributeMatches(String attribute, String lowerInput, String userInput) {
        if (attribute == null || attribute.isBlank() || lowerInput == null || lowerInput.isBlank()) {
            return false;
        }
        String value = attribute.replace('_', ' ').strip().toLowerCase(Locale.ROOT);
        if (!value.isBlank() && lowerInput.contains(value)) {
            return true;
        }
        String attrToken = FashionAttributeNormalizer.token(attribute);
        if (attrToken.isBlank()) {
            return false;
        }
        int maxLen = Math.min(userInput.length(), 8);
        for (int len = 1; len <= maxLen; len++) {
            for (int start = 0; start + len <= userInput.length(); start++) {
                String fragment = userInput.substring(start, start + len);
                if (fragment.isBlank()) {
                    continue;
                }
                if (attrToken.equals(FashionAttributeNormalizer.token(fragment))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 发送衣橱单品图片；publish 返回即已通过完成事件发送给微信用户。 */
    private void publishWardrobeItemImage(String userId, WardrobePreview preview) {
        completionPublisher.publish(new ImageTaskCompletionEvent(
                userId, "wardrobe_" + preview.item().id(), preview.imageBytes(), "", 0));
        log.info("Published wardrobe item image: itemId={}, user={}", preview.item().id(), anonymize(userId));
    }

    /** 同一用户同一衣橱单品在去重窗口内不重复补发，避免 LLM 二次 consult 重复刷图。 */
    private boolean shouldSkipWardrobeImage(String userId, long itemId) {
        String key = userId + ":wardrobe:" + itemId;
        long now = System.currentTimeMillis();
        Long lastSent = referenceImageSentAt.get(key);
        if (lastSent != null && now - lastSent < referenceImageDedupWindowMillis) {
            return true;
        }
        referenceImageSentAt.put(key, now);
        return false;
    }

    /**
     * 把参考穿搭图片下载并发送给用户，全部发送完成后再返回文案。
     *
     * <p>顺序保证：推荐穿搭时先发图、后发文本。图片在工具返回前就已逐张下载并
     * 通过 {@link ImageTaskCompletionEvent} 发给微信（发送完成后 publish 才返回），
     * 而文本由模型在本工具返回后才生成，因此图片必然先于文本到达。
     * <p>多张参考图彼此独立，提交到虚拟线程池 {@code fashionAgentParallelExecutor}
     * 并行下载+发送（串行实测 3 张约 14s，占工具调用总时长 40%），把耗时压到最慢一张。
     * 单张图片下载有 15 秒超时兜底（见 httpClient readTimeout），失败只记日志，
     * 不阻塞其余图片与最终文案。优先按 RAG 上下文里的 {@code [outfit_XXX]} 标记查
     * URL 映射表（不依赖 RAGFlow 分块内容是否保留链接）；查不到时回退为直接从
     * 上下文抓取 {@code fashion-reference/outfits/...} URL。
     */
    private void scheduleReferenceImages(String userId, FashionResult result) {
        if (result == null || result.ragContext() == null) {
            log.info("scheduleReferenceImages skipped: resultNull={} ragNull={}",
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
        List<SendUnit> units = planSendUnits(resolved.outfitId(), resolved.urls());
        log.info("scheduleReferenceImages: resolved {} urls -> {} send units",
                resolved.urls().size(), units.size());
        int sent = 0;
        List<java.util.concurrent.CompletableFuture<?>> sentFutures = new ArrayList<>();
        for (SendUnit unit : units) {
            if (sent >= MAX_REFERENCE_IMAGES) {
                break;
            }
            // 并行发送：各发送单元彼此独立，提交虚拟线程池并发下载+发送；
            // 不阻塞文本生成，仅登记完成信号，由文本发送前等待（保证图先于文）
            sentFutures.add(submitSendUnit(userId, unit));
            sent++;
        }
        if (!sentFutures.isEmpty() && imageSendGate != null) {
            imageSendGate.track(userId, java.util.concurrent.CompletableFuture
                    .allOf(sentFutures.toArray(new java.util.concurrent.CompletableFuture[0])));
        }
    }

    /**
     * 把参考图 URL 规划为发送单元：top+bottom 两件单品合并为一个拼图单元，
     * 其余非分割单品（如 overview 整体图）单独发送；方案里其他分割单品（叠穿第二件上衣、
     * 鞋、配饰等）不再单独发，避免同一方案一次发 3+ 张图（8.26：outfit 148 叠穿两件上衣
     * 曾多出第二件上衣单发）。无 outfit 编号或无法配对时保持原样逐张发送。
     */
    private List<SendUnit> planSendUnits(String outfitId, List<String> urls) {
        if (outfitId == null || imageResolver == null || urls == null || urls.isEmpty()) {
            return urls == null ? List.of() : urls.stream().map(SendUnit::single).toList();
        }
        List<ReferenceImageResolver.GarmentImage> garments = imageResolver.garmentsFor(outfitId);
        String topUrl = garmentUrl(garments, "top");
        String bottomUrl = garmentUrl(garments, "bottom");
        if (topUrl == null || bottomUrl == null) {
            return urls.stream().map(SendUnit::single).toList();
        }
        List<SendUnit> units = new ArrayList<>();
        for (String url : urls) {
            if (url.equals(topUrl) || url.equals(bottomUrl)) {
                continue; // top+bottom 合并为一张拼图
            }
            if (isGarmentImage(garments, url)) {
                continue; // 其余分割单品不单独发，避免冗余多图
            }
            units.add(SendUnit.single(url));
        }
        units.add(new SendUnit.Collage(topUrl, bottomUrl));
        return units;
    }

    /** url 是否属于该方案的分割单品图（含 top/bottom 之外的叠穿上衣、鞋、配饰等）。 */
    private static boolean isGarmentImage(List<ReferenceImageResolver.GarmentImage> garments, String url) {
        if (garments == null || url == null) {
            return false;
        }
        for (ReferenceImageResolver.GarmentImage garment : garments) {
            if (garment != null && url.equals(garment.url())) {
                return true;
            }
        }
        return false;
    }

    private static String garmentUrl(List<ReferenceImageResolver.GarmentImage> garments, String type) {
        if (garments == null) {
            return null;
        }
        for (ReferenceImageResolver.GarmentImage garment : garments) {
            if (garment != null && type.equals(safeGarment(garment.garment()))) {
                return garment.url();
            }
        }
        return null;
    }

    private static String safeGarment(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).strip();
    }

    /** 参考图发送单元：单张图，或需上下拼成一张的两件单品图（top+bottom）。 */
    private sealed interface SendUnit permits SendUnit.Single, SendUnit.Collage {
        record Single(String url) implements SendUnit {}
        record Collage(String topUrl, String bottomUrl) implements SendUnit {}

        static SendUnit single(String url) { return new Single(url); }
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

    /**
     * 异步提交一个发送单元并返回完成句柄；{@code executor} 未装配（单测等场景）时同步执行并返回已完成句柄。
     * 调用方 {@link #scheduleReferenceImages} 把句柄聚合为完成信号登记到 {@link ReferenceImageSendGate}，
     * 由主流程在发送文本前等待，保证"整套图 → 参考拼图 → 文本描述"的推送顺序且不阻塞文本生成。
     */
    private java.util.concurrent.CompletableFuture<?> submitSendUnit(String userId, SendUnit unit) {
        ExecutorService pool = executor;
        if (pool != null) {
            return java.util.concurrent.CompletableFuture.runAsync(() -> sendUnit(userId, unit), pool);
        }
        sendUnit(userId, unit);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /** 发送单个单元：单张图直接发；拼图单元先拼好再发一张，拼接不可用时降级逐张发送。 */
    private void sendUnit(String userId, SendUnit unit) {
        if (unit instanceof SendUnit.Single single) {
            downloadAndSendReferenceImage(userId, single.url());
            return;
        }
        SendUnit.Collage collage = (SendUnit.Collage) unit;
        byte[] top = downloadSafely(collage.topUrl());
        byte[] bottom = downloadSafely(collage.bottomUrl());
        byte[] combined = buildGarmentCollage(top, bottom);
        if (combined != null) {
            publishReferenceImage(userId, combined,
                    "collage(" + collage.topUrl() + " + " + collage.bottomUrl() + ")");
            return;
        }
        publishIfPresent(userId, top, collage.topUrl());
        publishIfPresent(userId, bottom, collage.bottomUrl());
    }

    /** 单张下载失败只丢该张，不影响同单元另一张图。 */
    private byte[] downloadSafely(String url) {
        try {
            return downloader.download(url);
        } catch (IOException exception) {
            log.warn("Failed to download reference outfit image {}: {}", url, exception.getMessage());
            return null;
        }
    }

    private void publishIfPresent(String userId, byte[] bytes, String source) {
        if (bytes != null && bytes.length > 0) {
            publishReferenceImage(userId, bytes, source);
        }
    }

    /** 同步下载参考图片，并通过完成事件发送给对应微信用户（publish 返回即已发送）。 */
    private void downloadAndSendReferenceImage(String userId, String url) {
        try {
            byte[] bytes = downloader.download(url);
            if (bytes != null && bytes.length > 0) {
                publishReferenceImage(userId, bytes, url);
            }
        } catch (Exception e) {
            log.warn("Failed to download reference outfit image {}: {}", url, e.getMessage());
        }
    }

    private void publishReferenceImage(String userId, byte[] bytes, String source) {
        completionPublisher.publish(new ImageTaskCompletionEvent(
                userId, "ref_" + UUID.randomUUID(), bytes, "", 0));
        log.info("Published reference outfit image: {}", source);
    }

    /** 把两张单品图上下拼接成一张 PNG 拼图；任一图片无法解码时返回 null（调用方降级逐张发送）。 */
    public static byte[] buildGarmentCollage(byte[] top, byte[] bottom) {
        if (top == null || top.length == 0 || bottom == null || bottom.length == 0) {
            return null;
        }
        try {
            BufferedImage topImage = ImageIO.read(new ByteArrayInputStream(top));
            BufferedImage bottomImage = ImageIO.read(new ByteArrayInputStream(bottom));
            if (topImage == null || bottomImage == null) {
                return null;
            }
            int width = Math.max(topImage.getWidth(), bottomImage.getWidth());
            BufferedImage topScaled = scaleToWidth(topImage, width);
            BufferedImage bottomScaled = scaleToWidth(bottomImage, width);
            int height = topScaled.getHeight() + bottomScaled.getHeight();
            BufferedImage collage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = collage.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(topScaled, 0, 0, null);
                graphics.drawImage(bottomScaled, 0, topScaled.getHeight(), null);
            } finally {
                graphics.dispose();
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(collage, "png", output)) {
                    return null;
                }
                return output.toByteArray();
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /** 等比缩放到目标宽度；已是目标宽度时原样返回，避免无谓重采样。 */
    private static BufferedImage scaleToWidth(BufferedImage image, int targetWidth) {
        if (image.getWidth() == targetWidth) {
            return image;
        }
        int height = Math.max(1, (int) Math.round(image.getHeight() * (double) targetWidth / image.getWidth()));
        BufferedImage scaled = new BufferedImage(targetWidth, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, targetWidth, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
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

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    /** 日志脱敏：避免完整微信 ID 落日志。 */
    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
