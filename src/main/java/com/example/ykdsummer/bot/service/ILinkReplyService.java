package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import com.example.ykdsummer.bot.file.FileInstructionService;
import com.example.ykdsummer.bot.file.FileSessionService;
import com.example.ykdsummer.bot.message.ILinkMessageType;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import com.example.ykdsummer.bot.video.VideoAnalysisService;
import io.github.morningwn.protocol.MessageItem;
import io.github.morningwn.protocol.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条微信消息的“回复路由器”：从 SDK 消息中提取内容，再决定走哪一种回复分支。
 *
 * <p>腾讯/iLink SDK 只告诉我们每个 {@link MessageItem} 的类型和内容，并不会替项目决定
 * “帮助”“生图”或者“调用大模型”。这些都是本类写明的本地规则。当前判断顺序是：</p>
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
    private final TtsVoiceSelectionService voiceSelectionService;
    private final FileSessionService fileSessions;
    private final FileInstructionService fileInstructionService;
    private final LocalImageAssetStore imageAssets;

    public ILinkReplyService(AiChatService aiChatService, ILinkMediaDownloader mediaDownloader,
                             ILinkFileDownloader fileDownloader,
                             VideoAnalysisService videoAnalysisService,
                             TtsVoiceSelectionService voiceSelectionService,
                             FileSessionService fileSessions,
                             FileInstructionService fileInstructionService) {
        this(aiChatService, mediaDownloader, fileDownloader, videoAnalysisService, voiceSelectionService,
                fileSessions, fileInstructionService, new LocalImageAssetStore());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ILinkReplyService(AiChatService aiChatService, ILinkMediaDownloader mediaDownloader,
                             ILinkFileDownloader fileDownloader,
                             VideoAnalysisService videoAnalysisService,
                             TtsVoiceSelectionService voiceSelectionService,
                             FileSessionService fileSessions,
                             FileInstructionService fileInstructionService,
                             LocalImageAssetStore imageAssets) {
        this.aiChatService = aiChatService;
        this.mediaDownloader = mediaDownloader;
        this.fileDownloader = fileDownloader;
        this.videoAnalysisService = videoAnalysisService;
        this.voiceSelectionService = voiceSelectionService;
        this.fileSessions = fileSessions;
        this.fileInstructionService = fileInstructionService;
        this.imageAssets = imageAssets;
    }

    public ILinkReply createReply(
            WeixinMessage message,
            List<MessageItem> items,
            ILinkRuntimeState.Snapshot status
    ) {
        // 第一步只整理 SDK item，不做网络请求：文字会合并，语音取微信已有转写，图片只记存在。
        ExtractedContent content = extract(items);

        // 没有文字要求的文件会短暂等待下一条普通话指令；真正处理时会登记到可持久恢复的文档版本仓库。
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

        // 固定命令只接受纯手打文字，避免语音转写为“帮助”时误触发本地命令。
        if (content.isPlainTextOnly()) {
            String commandReply = handleCommand(message.fromUserId(), content.prompt(), status);
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
            AiFile sourceFile = fileSessions.consume(message.fromUserId()).orElse(null);
            return toReply(fileInstructionService.process(
                    message.fromUserId(), content.prompt(), sourceFile));
        }

        // 微信语音已经有转写文字时，按普通问答处理，但不把转写误当成用户手打的工具命令。
        if (!content.hasImage()) {
            return new ILinkReply.Text(aiChatService.answer(message.fromUserId(), content.prompt(), List.of()));
        }

        List<AiImage> images;
        try {
            // 这里拿到的是已经从腾讯 CDN 下载并解密的原始图片字节。
            images = content.hasImage() ? mediaDownloader.downloadImages(items) : List.of();
        } catch (ILinkMediaDownloader.MediaProcessingException exception) {
            log.warn("Could not prepare iLink image, user={}, reason={}", anonymize(message.fromUserId()), exception.userMessage());
            return new ILinkReply.Text(exception.userMessage());
        }

        // 用户只发附件没有配文字时，补一个默认问题，保证 Responses API 至少有文字指令。
        String prompt = content.prompt().isBlank() ? DEFAULT_IMAGE_PROMPT : content.prompt();
        // 上传图也登记为可追踪资产。当前轮仍以原始图片走 Responses；之后用户只发文字说“改上一张图”时，
        // 模型可以通过 ImageTools 查询这个 assetId 与版本信息。
        List<StoredImage> storedImages = images.stream()
                .map(image -> imageAssets.saveIncoming(message.fromUserId(), prompt, image.bytes(), image.mediaType()))
                .toList();
        /*
         * 图片先落成 img_* 资产，再以纯文本 Agent 入口处理用户要求。这样“把这只猫变白”在首次上传时
         * 也能按 get_current_image/list_recent_images -> inspect_image(Responses 视觉识别) ->
         * create_image_revision 的工具链执行，而不是只能在下一条消息才开始修改。
         */
        String modelPrompt = prompt + "\n\n## 本轮上传图片资源\n"
                + "用户刚上传的图片已安全登记为：\n"
                + storedImages.stream()
                .map(image -> "- image assetId=" + image.assetId() + "，版本 v" + image.version())
                .collect(java.util.stream.Collectors.joining("\n"))
                + "\n如果回答、判断或修改依赖图片真实内容，必须先调用 inspect_image；"
                + "若用户要求修改图片，再调用 create_image_revision，并使用对应 assetId。";
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
        ExtractedContent content = extract(items == null ? List.of() : items);
        // 意图由 Agent 在模型调用时判断，接入层不再依据“生图：”等前缀猜测。
        return false;
    }

    /** 图片、文件和视频需下载、解码或分析，不能占用后续普通文字的顺序队列。 */
    boolean isMediaMessage(List<MessageItem> items) {
        ExtractedContent content = extract(items == null ? List.of() : items);
        return content.hasImage() || content.hasFile() || content.hasVideo();
    }

    /** 只检查消息是否包含 SDK 已解析的 VideoItem，不做下载或抽帧。 */
    boolean isVideoMessage(List<MessageItem> items) {
        return extract(items == null ? List.of() : items).hasVideo();
    }

    private String handleCommand(
            String userId,
            String prompt,
            ILinkRuntimeState.Snapshot status
    ) {
        /*
         * switch 使用 trim 后的完整字符串，因此“帮助我分析代码”不是“帮助”命令，
         * 会继续交给大模型。清空时使用 fromUserId，只删除发命令者自己的内存记录。
         */
        String command = prompt.trim();
        return switch (command) {
            case "帮助" -> "支持的功能：\n"
                    + "1. 发送普通文字与 AI 对话\n"
                    + "2. 发送图片，并附上你的问题\n"
                    + "3. 发送带微信转写文字的语音\n"
                    + "4. 发送“状态”查看运行状态\n"
                    + "5. 发送“清空”清除自己的上下文\n"
                    + "6. 直接说“帮我画一张……”即可让 AI 自主判断是否生图\n"
                    + "7. 直接说“请用语音回答……”即可生成 MP3 文件\n"
                    + "8. 可直接说“有哪些音色”“换成女声”“恢复默认音色”\n"
                    + "9. 发送 60 秒以内的短视频进行画面与语音联合分析\n"
                    + "10. 发送文件后直接用普通话说明要求，例如“帮我把这个变成 PDF”\n"
                    + "11. 不上传文件也可直接说“写一份 Word 报告”或“生成 Excel 表格”";
            case "状态" -> "微信连接：" + status.connectionStatus() + "\n"
                    + "长轮询：" + (status.polling() ? "运行中" : "未运行") + "\n"
                    + "AI：" + (aiChatService.isEnabled() ? "已启用" : "未启用") + "\n"
                    + "模型：" + (aiChatService.isEnabled() ? aiChatService.model() : "-") + "\n"
                    + "当前音色：" + voiceSelectionService.current(userId).display();
            case "清空" -> {
                aiChatService.clear(userId);
                fileSessions.clear(userId);
                imageAssets.clearCurrent(userId);
                fileInstructionService.clearCurrentDocumentPointer(userId);
                yield "已清空你的聊天记录和临时会话缓存；已保存的图片、文档版本和音色设置未删除";
            }
            default -> null;
        };
    }

    private static ExtractedContent extract(List<MessageItem> items) {
        List<String> textParts = new ArrayList<>();
        boolean hasImage = false;
        boolean hasFile = false;
        boolean hasVideo = false;
        boolean hasVoiceWithoutTranscript = false;
        boolean hasPlainText = false;
        boolean hasVoiceTranscript = false;

        for (MessageItem item : items) {
            if (item == null) {
                continue;
            }
            // item.type() 是协议整数；ILinkMessageType.from 只负责翻译成可读枚举。
            ILinkMessageType type = ILinkMessageType.from(item.type());
            if (type == ILinkMessageType.TEXT && item.textItem() != null) {
                addNonBlank(textParts, item.textItem().text());
                hasPlainText = true;
            } else if (type == ILinkMessageType.VOICE && item.voiceItem() != null) {
                // voiceItem.text() 是微信/iLink 已提供的转写，不是本项目自己识别音频。
                String transcript = item.voiceItem().text();
                if (transcript == null || transcript.isBlank()) {
                    hasVoiceWithoutTranscript = true;
                } else {
                    addNonBlank(textParts, transcript);
                    hasVoiceTranscript = true;
                }
            } else if (type == ILinkMessageType.IMAGE && item.imageItem() != null) {
                hasImage = true;
            } else if (type == ILinkMessageType.FILE && item.fileItem() != null) {
                hasFile = true;
            } else if (type == ILinkMessageType.VIDEO && item.videoItem() != null) {
                hasVideo = true;
            }
        }
        return new ExtractedContent(
                String.join("\n", textParts).trim(),
                hasImage,
                hasFile,
                hasVideo,
                hasVoiceWithoutTranscript,
                hasPlainText,
                hasVoiceTranscript
        );
    }

    private static void addNonBlank(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    /** 本次提取的临时结果，只在处理这一条微信消息时使用。 */
    private record ExtractedContent(
            String prompt,
            boolean hasImage,
            boolean hasFile,
            boolean hasVideo,
            boolean hasVoiceWithoutTranscript,
            boolean hasPlainText,
            boolean hasVoiceTranscript
    ) {
        private boolean isPlainTextOnly() {
            return hasPlainText && !hasVoiceTranscript && !hasImage && !hasFile && !hasVideo;
        }
    }
}
