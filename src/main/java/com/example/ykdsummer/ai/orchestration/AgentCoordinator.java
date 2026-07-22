package com.example.ykdsummer.ai.orchestration;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.tool.DocumentTools;
import com.example.ykdsummer.ai.tool.ImageGenerationTools;
import com.example.ykdsummer.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Agent 编排器。负责：
 * <ol>
 *   <li>创建文件工作区会话</li>
 *   <li>委托 {@link AiChatService}（管理多轮记忆 + Spring AI Function Calling + 工具调用）</li>
 *   <li>消费 {@code pendingImage} / {@code pendingDocument}，按结果类型返回</li>
 *   <li>清理会话</li>
 * </ol>
 *
 * <p>实际的 LLM 调用和工具链编排由 {@link AiChatService} → 网关完成，
 * 本类专注于"翻译"AI 回复和文件/图片的传递。</p>
 */
@Service
public class AgentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentCoordinator.class);

    private final FileStorageService fileStorage;
    private final AiChatService aiChatService;

    public AgentCoordinator(
            FileStorageService fileStorage,
            AiChatService aiChatService
    ) {
        this.fileStorage = fileStorage;
        this.aiChatService = aiChatService;
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

            // 通过 AiChatService 执行：
            // - 管理多轮对话记忆（Caffeine 缓存）
            // - 调用 LLM Function Calling（网关自动注册了全部 @Tool）
            // - 错误分类与友好提示
            List<AiFile> files = sourceFile != null ? List.of(sourceFile) : List.of();
            String response = aiChatService.answer(userId, prompt, List.of(), files);

            // 消费工具调用产生的二进制结果（图片/文件）
            DocumentTools.PendingDocument doc = DocumentTools.consumePendingDocument();
            byte[] imageBytes = ImageGenerationTools.consumePendingImage();

            if (doc != null) {
                log.info("Agent produced document: {} ({} bytes)", doc.fileName(), doc.bytes().length);
                return new AgentResult(response, doc.fileName(), doc.bytes());
            }
            if (imageBytes != null) {
                log.info("Agent produced image: {} bytes", imageBytes.length);
                return AgentResult.image(imageBytes);
            }

            // 纯文本结果
            log.info("Agent completed: userId={}, sessionId={}", anonymize(userId), sessionId);
            return AgentResult.text(response == null || response.isBlank() ? "处理完成" : response);

        } catch (Exception e) {
            log.warn("Agent failed: userId={}, sessionId={}", anonymize(userId), sessionId, e);
            return AgentResult.text("处理请求时遇到错误：" + e.getMessage());
        } finally {
            AgentSessionContext.clear();
        }
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    private static String truncate(String text) {
        return text == null ? "" : text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}
