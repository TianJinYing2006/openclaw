package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.bot.file.FileInstructionService;
import com.example.ykdsummer.bot.file.FileSessionService;
import com.example.ykdsummer.bot.message.CommandHandler;
import com.example.ykdsummer.bot.message.MessageExtractor;
import com.example.ykdsummer.bot.message.MessageExtractor.ExtractedContent;
import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import com.example.ykdsummer.bot.video.VideoAnalysisService;
import io.github.morningwn.protocol.MessageItem;
import io.github.morningwn.protocol.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 一条微信消息的"回复路由器"：从 SDK 消息中提取内容，再决定走哪一种回复分支。
 *
 * <p>腾讯/iLink SDK 只告诉我们每个 {@link MessageItem} 的类型和内容，并不会替项目决定
 * "帮助""生图"或者"调用大模型"。这些都是本类写明的本地规则。当前判断顺序是：</p>
 * <ol>
 *     <li>无图片时，先判断整条文字是否精确等于固定命令；</li>
 *     <li>只有空语音或不支持类型时，直接返回固定提示；</li>
 *     <li>有入站图片或文件时先从腾讯 CDN 下载解密；</li>
 *     <li>其余文字、语音转写、图片和文件问题交给 {@link AiChatService}；模型可自主调用生图工具。</li>
 * </ol>
 */
@Service
public class ILinkReplyService {

    static final String VOICE_WITHOUT_TEXT_REPLY = "暂时无法识别，请改发文字";
    static final String UNSUPPORTED_REPLY = "暂时只支持文字、图片、文件、短视频和带转写文字的语音";
    static final String MIXED_VIDEO_ATTACHMENT_REPLY = "暂不支持在同一条消息中同时发送视频和图片或文件";
    static final String DEFAULT_IMAGE_PROMPT = "请描述这张图片";

    private static final Logger log = LoggerFactory.getLogger(ILinkReplyService.class);

    private final AiChatService aiChatService;
    private final ILinkMediaDownloader mediaDownloader;
    private final ILinkFileDownloader fileDownloader;
    private final VideoAnalysisService videoAnalysisService;
    private final FileSessionService fileSessions;
    private final FileInstructionService fileInstructionService;
    private final LocalImageAssetStore imageAssets;
    private final MessageExtractor messageExtractor;
    private final CommandHandler commandHandler;

    /** 向后兼容的测试构造器；生产环境使用 {@link Autowired} 完整构造器。 */
    public ILinkReplyService(AiChatService aiChatService, ILinkMediaDownloader mediaDownloader,
                             ILinkFileDownloader fileDownloader,
                             VideoAnalysisService videoAnalysisService,
                             TtsVoiceSelectionService voiceSelectionService,
                             FileSessionService fileSessions,
                             FileInstructionService fileInstructionService,
                             LocalImageAssetStore imageAssets) {
        this(aiChatService, mediaDownloader, fileDownloader, videoAnalysisService, fileSessions,
                fileInstructionService, imageAssets,
                new MessageExtractor(),
                new CommandHandler(aiChatService, voiceSelectionService, fileSessions, imageAssets, fileInstructionService));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ILinkReplyService(AiChatService aiChatService, ILinkMediaDownloader mediaDownloader,
                             ILinkFileDownloader fileDownloader,
                             VideoAnalysisService videoAnalysisService,
                             FileSessionService fileSessions,
                             FileInstructionService fileInstructionService,
                             LocalImageAssetStore imageAssets,
                             MessageExtractor messageExtractor,
                             CommandHandler commandHandler) {
        this.aiChatService = aiChatService;
        this.mediaDownloader = mediaDownloader;
        this.fileDownloader = fileDownloader;
        this.videoAnalysisService = videoAnalysisService;
        this.fileSessions = fileSessions;
        this.fileInstructionService = fileInstructionService;
        this.imageAssets = imageAssets;
        this.messageExtractor = messageExtractor;
        this.commandHandler = commandHandler;
    }

    public ILinkReply createReply(
            WeixinMessage message,
            List<MessageItem> items,
            ILinkRuntimeState.Snapshot status
    ) {
        ExtractedContent content = messageExtractor.extract(items);

        if (content.hasFile()) {
            if (content.hasImage() || content.hasVideo()) {
                return new ILinkReply.Text("文档模式一次只能发送一个文件，不能同时附带图片或视频");
            }
            try {
                List<AiFile> uploaded = fileDownloader.downloadFiles(items);
                if (uploaded.size() != 1) {
                    return new ILinkReply.Text("文档模式一次只能发送一个文件");
                }
                AiFile file = uploaded.getFirst();
                if (!content.prompt().isBlank()) {
                    return toReply(fileInstructionService.process(
                            message.fromUserId(), content.prompt(), file));
                }
                fileSessions.cache(message.fromUserId(), file);
                return new ILinkReply.Text("已收到文件，请告诉我怎么处理");
            } catch (ILinkFileDownloader.FileProcessingException exception) {
                log.warn("Could not prepare iLink file, user={}, reason={}",
                        anonymize(message.fromUserId()), exception.userMessage());
                return new ILinkReply.Text(exception.userMessage());
            }
        }

        // 固定命令只接受纯手打文字，避免语音转写为"帮助"时误触发本地命令。
        if (content.isPlainTextOnly()) {
            String commandReply = commandHandler.handle(
                    message.fromUserId(), content.prompt(), status);
            if (commandReply != null) {
                return new ILinkReply.Text(commandReply);
            }
        }

        // 语音没有 text 字段时，本项目没有额外语音识别服务，所以不能继续调用文字模型。
        if (content.prompt().isBlank() && !content.hasImage() && !content.hasFile() && !content.hasVideo()) {
            return new ILinkReply.Text(content.hasVoiceWithoutTranscript() ? VOICE_WITHOUT_TEXT_REPLY : UNSUPPORTED_REPLY);
        }

        /*
         * 视频与图片在第一版不混合：视频本身已经会抽出 3～10 张帧，如果再加入用户图片，
         * 很难向模型清楚说明哪些属于视频、哪些属于额外附件，也更容易超过网关限制。
         */
        if (content.hasVideo()) {
            if (content.hasImage() || content.hasFile()) {
                return new ILinkReply.Text(MIXED_VIDEO_ATTACHMENT_REPLY);
            }
            return new ILinkReply.Text(videoAnalysisService.analyze(
                    message.fromUserId(), content.prompt(), items));
        }

        /*
         * 所有纯文本都进入同一模型入口。模型根据 Tool 描述决定是否调用 DocumentTools、ImageTools、
         * SpeechTools 等能力；接入层不再解析 FILE_GEN 或依赖人工前缀。
         */
        if (content.isPlainTextOnly()) {
            AgentSessionContext.set(message.fromUserId(), message.sessionId(), message.contextToken());
            AiFile sourceFile = fileSessions.consume(message.fromUserId()).orElse(null);
            return toReply(fileInstructionService.process(
                    message.fromUserId(), content.prompt(), sourceFile));
        }

        // 微信语音已经有转写文字时，按普通问答处理，但不把转写误当成用户手打的工具命令。
        if (!content.hasImage()) {
            AgentSessionContext.set(message.fromUserId(), message.sessionId(), message.contextToken());
            return new ILinkReply.Text(aiChatService.answer(message.fromUserId(), content.prompt(), List.of()));
        }

        List<AiImage> images;
        try {
            images = content.hasImage() ? mediaDownloader.downloadImages(items) : List.of();
        } catch (ILinkMediaDownloader.MediaProcessingException exception) {
            log.warn("Could not prepare iLink image, user={}, reason={}",
                    anonymize(message.fromUserId()), exception.userMessage());
            return new ILinkReply.Text(exception.userMessage());
        }

        String prompt = content.prompt().isBlank() ? DEFAULT_IMAGE_PROMPT : content.prompt();
        List<StoredImage> storedImages = images.stream()
                .map(image -> imageAssets.saveIncoming(message.fromUserId(), prompt, image.bytes(), image.mediaType()))
                .toList();
        String modelPrompt = prompt + "\n\n## 本轮上传图片资源\n"
                + "用户刚上传的图片已安全登记为：\n"
                + storedImages.stream()
                .map(image -> "- image assetId=" + image.assetId() + "，版本 v" + image.version())
                .collect(Collectors.joining("\n"))
                + "\n如果回答、判断或修改依赖图片真实内容，必须先调用 inspect_image；"
                + "若用户要求修改图片，再调用 create_image_revision，并使用对应 assetId。";
        AgentSessionContext.set(message.fromUserId(), message.sessionId(), message.contextToken());
        return toReply(aiChatService.answerWithInternalPromptRich(
                message.fromUserId(), prompt, modelPrompt, List.of()));
    }

    private static ILinkReply toReply(FileInstructionService.Result result) {
        if (result.hasImage()) {
            return new ILinkReply.Image(result.imageBytes(), result.text());
        }
        if (result.hasAudio()) {
            return new ILinkReply.AudioFile(result.audioFileName(), result.audioBytes());
        }
        return result.hasFile()
                ? new ILinkReply.DocumentFile(result.fileName(), result.bytes(),
                result.text().isBlank() ? "文件已生成" : result.text())
                : new ILinkReply.Text(result.text());
    }

    private static ILinkReply toReply(AiChatService.AssistantAnswer answer) {
        return answer.artifacts().stream()
                .filter(artifact -> artifact.bytes() != null)
                .findFirst()
                .map(artifact -> switch (artifact.type()) {
                    case IMAGE -> (ILinkReply) new ILinkReply.Image(artifact.bytes(), answer.text());
                    case AUDIO -> new ILinkReply.AudioFile(artifact.fileName(), artifact.bytes());
                    case DOCUMENT -> new ILinkReply.DocumentFile(artifact.fileName(), artifact.bytes(), answer.text());
                })
                .orElseGet(() -> new ILinkReply.Text(answer.text()));
    }

    /**
     * 供 {@link ILinkBotService} 在真正处理回复之前选择线程池。这里只做同样的内容提取和
     * 格式判断，不调用图片服务，所以很快就能返回。
     */
    boolean isImageGenerationMessage(List<MessageItem> items) {
        messageExtractor.extract(items == null ? List.of() : items);
        return false;
    }

    /** 只检查消息是否包含 SDK 已解析的 VideoItem，不做下载或抽帧。 */
    boolean isVideoMessage(List<MessageItem> items) {
        return messageExtractor.extract(items == null ? List.of() : items).hasVideo();
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
