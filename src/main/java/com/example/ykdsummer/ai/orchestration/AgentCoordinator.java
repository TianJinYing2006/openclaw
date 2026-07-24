package com.example.ykdsummer.ai.orchestration;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.tool.DocumentTools;
import com.example.ykdsummer.ai.tool.ImageGenerationTools;
import com.example.ykdsummer.ai.tool.TtsTools;
import com.example.ykdsummer.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Agent 锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷
 * <ol>
 *   <li>锟斤拷锟斤拷锟侥硷拷锟斤拷锟斤拷锟斤拷锟结话</li>
 *   <li>委锟斤拷 {@link AiChatService}锟斤拷锟斤拷锟斤拷锟斤拷旨锟斤拷锟?+ Spring AI Function Calling + 锟斤拷锟竭碉拷锟矫ｏ拷</li>
 *   <li>锟斤拷锟斤拷 {@code pendingImage} / {@code pendingDocument}锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷头锟斤拷锟?/li>
 *   <li>锟斤拷锟斤拷峄?/li>
 * </ol>
 */
@Service
public class AgentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentCoordinator.class);

    private final FileStorageService fileStorage;
    private final AiChatService aiChatService;
    private final TtsTools ttsTools;

    public AgentCoordinator(
            FileStorageService fileStorage,
            AiChatService aiChatService,
            TtsTools ttsTools
    ) {
        this.fileStorage = fileStorage;
        this.aiChatService = aiChatService;
        this.ttsTools = ttsTools;
    }

    /** Agent 执行结果，携带文本回复及可能的图片/文件/音频附件。*/
    public record AgentResult(String text, String fileName, byte[] bytes, String kind) {
        public boolean hasFile() { return "document".equals(kind); }
        public boolean hasImage() { return "image".equals(kind); }
        public boolean hasAudio() { return "audio".equals(kind); }

        public static AgentResult text(String text) { return new AgentResult(text, null, null, "text"); }
        public static AgentResult file(String fileName, byte[] bytes) { return new AgentResult("", fileName, bytes, "document"); }
        public static AgentResult image(byte[] bytes) { return new AgentResult("", null, bytes, "image"); }
        public static AgentResult audio(String fileName, byte[] bytes) { return new AgentResult("", fileName, bytes, "audio"); }
        public static AgentResult documentWithText(String text, String fileName, byte[] bytes) {
            return new AgentResult(text, fileName, bytes, "document");
        }
    }

    /**
     * 执锟斤拷一锟斤拷 Agent 锟斤拷锟脚★拷
     *
     * @param userId     锟矫伙拷 ID
     * @param prompt     锟矫伙拷锟斤拷锟斤拷
     * @param sourceFile 锟矫伙拷锟斤拷锟斤拷锟斤拷锟侥硷拷锟斤拷锟斤拷选锟斤拷
     * @return 锟斤拷锟脚斤拷锟?
     */
    public AgentResult execute(String userId, String prompt, List<AiImage> images, AiFile sourceFile) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        fileStorage.createSession(userId, sessionId);
        AgentSessionContext.set(userId, sessionId);

        try {
            log.info("Agent start: userId={}, sessionId={}, prompt='{}'",
                    anonymize(userId), sessionId, truncate(prompt));

            List<AiFile> files = sourceFile != null ? List.of(sourceFile) : List.of();
            String response = null;

            // B2甲：多步编排循环，最多5次迭代
            int maxIterations = 5;
            for (int i = 0; i < maxIterations; i++) {
                response = aiChatService.answer(userId, prompt, images == null ? List.of() : images, files);

                // 每次迭代后检查 artifacts
                List<DocumentTools.PendingDocument> pendingDocs = DocumentTools.consumePendingDocuments();
                List<ImageGenerationTools.PendingImage> pendingImages = ImageGenerationTools.consumePendingImages();
                List<TtsTools.PendingAudio> pendingAudios = TtsTools.consumePendingAudios();
                DocumentTools.PendingDocument doc = pendingDocs.isEmpty() ? null : pendingDocs.getLast();
                ImageGenerationTools.PendingImage latestImage = pendingImages.isEmpty() ? null : pendingImages.getLast();
                byte[] imageBytes = latestImage != null ? latestImage.bytes() : null;
                TtsTools.PendingAudio latestAudio = pendingAudios.isEmpty() ? null : pendingAudios.getLast();

                if (doc != null) {
                    log.info("Agent produced document at iteration {}: {} ({} bytes)", i + 1, doc.fileName(), doc.bytes().length);
                    return AgentResult.documentWithText(response, doc.fileName(), doc.bytes());
                }
                if (imageBytes != null) {
                    log.info("Agent produced image at iteration {}: {} bytes", i + 1, imageBytes.length);
                    return AgentResult.image(imageBytes);
                }
                if (latestAudio != null) {
                    log.info("Agent produced audio at iteration {}: {} ({} bytes)", i + 1, latestAudio.fileName(), latestAudio.bytes().length);
                    return AgentResult.audio(latestAudio.fileName(), latestAudio.bytes());
                }
                // 有内容且无 artifacts → 结束循环
                if (response != null && !response.isBlank()) {
                    break;
                }
                log.info("Agent iteration {}: produced no output, retrying...", i + 1);
            }

            log.info("Agent completed: userId={}, sessionId={}, responseLength={}",
                    anonymize(userId), sessionId, response == null ? 0 : response.length());

            // B4乙：兜底策略 — 用户要求语音但模型未调用 TTS，自动强制合成语音
            if (response != null && !response.isBlank() && hasVoiceIntent(prompt)) {
                log.info("User requested voice but no TTS was produced, forcing fallback synthesis");
                ttsTools.textToSpeech(response, null);
                List<TtsTools.PendingAudio> forcedAudios = TtsTools.consumePendingAudios();
                if (!forcedAudios.isEmpty()) {
                    TtsTools.PendingAudio forcedAudio = forcedAudios.getLast();
                    log.info("Fallback TTS produced audio: {} ({} bytes)", forcedAudio.fileName(), forcedAudio.bytes().length);
                    return AgentResult.audio(forcedAudio.fileName(), forcedAudio.bytes());
                }
            }

            return AgentResult.text(response == null || response.isBlank() ? "操作完成" : response);
        } catch (Exception e) {
            log.warn("Agent failed: userId={}, sessionId={}", anonymize(userId), sessionId, e);
            // B1乙：清理 pending artifacts，防止 ThreadLocal 残留导致重复回复
            DocumentTools.consumePendingDocuments();
            ImageGenerationTools.consumePendingImages();
            TtsTools.consumePendingAudios();
            return AgentResult.text("操作执行时出现问题：" + e.getMessage());
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

    /** 检测用户 prompt 是否包含语音回复意图，用于 TTS 兜底合成判断 */
    private static boolean hasVoiceIntent(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        return prompt.contains("用语音读出来") || prompt.contains("用语音回复")
            || prompt.contains("说给我听") || prompt.contains("转语音")
            || prompt.contains("读给我听") || prompt.contains("语音回复")
            || prompt.contains("用语音说") || prompt.contains("用语音");
    }
}

