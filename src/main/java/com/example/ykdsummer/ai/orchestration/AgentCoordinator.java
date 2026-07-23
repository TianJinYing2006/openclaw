package com.example.ykdsummer.ai.orchestration;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.tool.DocumentTools;
import com.example.ykdsummer.ai.tool.ImageGenerationTools;
import com.example.ykdsummer.ai.tool.WebSearchTools;
import com.example.ykdsummer.storage.FileStorageService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent 编排器。负责：
 * <ol>
 *   <li>创建文件工作区会话</li>
 *   <li>预搜索：检测搜索关键词，直接调用 {@link WebSearchTools#searchWeb} 注入结果</li>
 *   <li>委托 {@link AiChatService}（管理多轮记忆 + Spring AI Function Calling + 工具调用）</li>
 *   <li>消费 {@code pendingImage} / {@code pendingDocument}，按结果类型返回</li>
 *   <li>清理会话</li>
 * </ol>
 *
 * <p>预搜索先注入结果，避免模型因函数调用不可靠而无法获取实时信息。
 * 模型仍然可以自主调用 {@code search_web} 工具（如需要补充搜索），
 * 但系统提示词已告知模型：如果消息中已包含搜索结果，直接使用即可。</p>
 */
@Service
public class AgentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentCoordinator.class);

    /** 匹配搜索/热点/新闻等关键词，触发预搜索 */
    private static final Pattern SEARCH_PATTERN = Pattern.compile(
            "搜索|搜一搜|热点|新闻|资讯|热搜|实时|发生了什么|最新", Pattern.CASE_INSENSITIVE);

    /**
     * 内容去重缓存：30 秒内同一用户发送相同的原始 prompt，直接返回缓存结果。
     * 解决 SDK 用不同 messageId 重复投递同一条消息导致 AI 被多次调用的问题。
     * key = "userId::原始prompt"（搜索增强前），value = 上次的 AgentResult。
     */
    private final Cache<String, AgentResult> recentResults;

    private final FileStorageService fileStorage;
    private final AiChatService aiChatService;
    private final WebSearchTools webSearchTools;

    public AgentCoordinator(
            FileStorageService fileStorage,
            AiChatService aiChatService,
            WebSearchTools webSearchTools
    ) {
        this.fileStorage = fileStorage;
        this.aiChatService = aiChatService;
        this.webSearchTools = webSearchTools;
        this.recentResults = Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build();
    }

    /** 编排结果：纯文本、文件或图片。 */
    public record AgentResult(String text, String fileName, byte[] bytes) {
        public boolean hasFile() { return fileName != null && !fileName.isBlank() && bytes != null; }
        public boolean hasImage() { return bytes != null && (fileName == null || fileName.isBlank()); }

        public static AgentResult text(String text) { return new AgentResult(text, null, null); }
        public static AgentResult file(String fileName, byte[] bytes) { return new AgentResult("", fileName, bytes); }
        public static AgentResult image(byte[] bytes) { return new AgentResult("", null, bytes); }
    }

    /**
     * 执行一次 Agent 编排。
     *
     * @param userId     用户 ID
     * @param prompt     用户输入
     * @param sourceFile 用户附带的文件（可选）
     * @return 编排结果
     */
    public AgentResult execute(String userId, String prompt, AiFile sourceFile) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        fileStorage.createSession(userId, sessionId);
        AgentSessionContext.set(userId, sessionId);

        try {
            log.info("Agent start: userId={}, sessionId={}, prompt='{}'",
                    anonymize(userId), sessionId, truncate(prompt));

            // ===== 内容去重：基于原始 prompt 在 30 秒内拦截重复请求 =====
            // 必须在预搜索之前检查，因为预搜索结果动态变化会导致 AiChatService 的去重 key 不同
            String cacheKey = userId + "::" + prompt;
            AgentResult cached = recentResults.getIfPresent(cacheKey);
            if (cached != null) {
                log.info("Dedup hit for user={}, returning cached result", anonymize(userId));
                return cached;
            }

            // ===== 预搜索：检测搜索关键词，直接调用 search_web 注入结果 =====
            // 避免模型因函数调用不可靠而不执行搜索
            String augmentedPrompt = prompt;
            boolean searchResultsInjected = false; // 标记预搜索是否成功注入了结果
            String searchResult = ""; // 保存搜索结果，AI 胡编时用于兜底
            if (SEARCH_PATTERN.matcher(prompt).find()) {
                log.info("Detected search intent, performing pre-search...");
                String searchQuery = extractSearchQuery(prompt);
                searchResult = webSearchTools.searchWeb(searchQuery);
                log.info("Pre-search result length: {}", searchResult.length());
                // 只在搜索返回有效结果时注入，搜索失败时不干扰模型判断
                if (isValidSearchResult(searchResult)) {
                    searchResultsInjected = true;
                    augmentedPrompt = prompt + "\n\n[搜索结果]\n" + searchResult
                            + "\n\n注意：以上搜索结果已为你提供，请直接基于这些信息回答。"
                            + "不要再调用 search_web、feishu_doc_create、generate_document 等工具，"
                            + "用户需要的答案已经在搜索结果中，直接整理给用户即可。";
                } else {
                    log.info("Pre-search returned no valid results, skipping augmentation");
                }
            }

            // 通过 AiChatService 执行，模型仍可使用 search_web 等工具
            List<AiFile> files = sourceFile != null ? List.of(sourceFile) : List.of();
            String response = aiChatService.answer(userId, augmentedPrompt, List.of(), files);

            // ===== 兜底：预搜索成功但 AI 胡编搜索不可用 → 强制用搜索结果替代 =====
            // qwen3.7-plus 等模型有时会忽略 prompt 中的搜索结果，自行编造"API Key 未配置"等错误。
            if (searchResultsInjected && hallucinatesSearchFailure(response)) {
                log.warn("AI hallucinated search failure despite valid results, overriding response");
                response = formatSearchResultResponse(searchResult);
            }

            // 消费工具调用产生的二进制结果（图片/文件）
            DocumentTools.PendingDocument doc = DocumentTools.consumePendingDocument();
            byte[] imageBytes = ImageGenerationTools.consumePendingImage();

            AgentResult result;
            if (doc != null) {
                log.info("Agent produced document: {} ({} bytes)", doc.fileName(), doc.bytes().length);
                result = new AgentResult(response, doc.fileName(), doc.bytes());
            } else if (imageBytes != null) {
                log.info("Agent produced image: {} bytes", imageBytes.length);
                result = AgentResult.image(imageBytes);
            } else {
                // 纯文本结果
                log.info("Agent completed: userId={}, sessionId={}", anonymize(userId), sessionId);
                result = AgentResult.text(response == null || response.isBlank() ? "处理完成" : response);
            }

            // 缓存结果用于后续去重
            recentResults.put(cacheKey, result);
            return result;

        } catch (Exception e) {
            log.warn("Agent failed: userId={}, sessionId={}", anonymize(userId), sessionId, e);
            return AgentResult.text("处理请求时遇到错误：" + e.getMessage());
        } finally {
            AgentSessionContext.clear();
        }
    }

    /**
     * 从用户消息中提取搜索关键词。去掉 "搜索"/"搜一下"/"查一下" 等前缀后取前 50 字。
     */
    /**
     * 判断预搜索是否返回了有效的结果。有效结果以 "找到 X 条结果" 开头，
     * 错误/空结果（如"搜索无结果""搜索失败"等）不注入，避免误导模型。
     */
    private static boolean isValidSearchResult(String result) {
        return result != null && result.startsWith("找到 ");
    }

    /**
     * 检测 AI 是否胡编了"搜索不可用"类消息。
     * 当预搜索已成功注入结果，但 AI 仍回复"API Key 未配置"等内容时返回 true。
     */
    private static boolean hallucinatesSearchFailure(String response) {
        if (response == null || response.isBlank()) return false;
        String r = response.toLowerCase();
        // 匹配 "API Key 未配置/没有配置"、"搜索功能无法使用/不可用"、"搜索失败" 等胡编模式
        boolean hasApiKeyPhrase = r.contains("apikey") || r.contains("api_key")
                || r.contains("api-key") || r.contains("api key");
        boolean hasConfigPhrase = r.contains("未配置") || r.contains("没有配置")
                || r.contains("未设置") || r.contains("没有设置");
        boolean hasSearchFailPhrase = (r.contains("搜索") || r.contains("搜索功能"))
                && (r.contains("无法使用") || r.contains("不可用") || r.contains("失败")
                || r.contains("没法") || r.contains("暂时"));
        return (hasApiKeyPhrase && hasConfigPhrase) || hasSearchFailPhrase;
    }

    /**
     * 将搜索结果格式化为可直接回复用户的文本。
     * 当 AI 胡编时用此方法替代模型输出。
     */
    private static String formatSearchResultResponse(String searchResult) {
        // 解析搜索结果：去掉 "找到 X 条结果：\n\n" 前缀，保留条目列表
        if (searchResult == null || searchResult.isBlank()) {
            return "暂时没有找到相关信息。";
        }
        // 直接去掉前缀行，保留条目
        String body = searchResult.replaceFirst("^找到 \\d+ 条结果：\n\n", "");
        // 去掉每行开头的链接和日期，只保留标题和摘要
        StringBuilder sb = new StringBuilder("为你找到以下信息：\n\n");
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("链接：") || trimmed.startsWith("日期：")) {
                continue;
            }
            sb.append(line).append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * 从用户消息中提取搜索关键词：
     * <ol>
     *   <li>去掉前缀（搜索、查一下等）</li>
     *   <li>去掉尾缀（加入云端、保存、生成文档等与搜索无关的操作意图）</li>
     * </ol>
     */
    private static String extractSearchQuery(String prompt) {
        String query = prompt
                .replaceAll("^(帮我)?(搜索|搜一搜|搜一下|查一下|查找|查询|看看|找一下)", "")
                .replaceAll("(加入|保存|写入|生成|创建)(.*)(云文档|文档|文件|笔记)$", "")
                .replaceAll("[\"「『]", "")
                .trim();
        return query.length() > 50 ? query.substring(0, 50) : query;
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    private static String truncate(String text) {
        return text == null ? "" : text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}
