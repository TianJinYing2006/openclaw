package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.bot.audio.SpeechSynthesisException;
import com.example.ykdsummer.bot.audio.TextToSpeechService;
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
 *     <li>无图片时，再判断是否以“生图：”或“生图:”开头；</li>
 *     <li>只有空语音或不支持类型时，直接返回固定提示；</li>
 *     <li>有入站图片或文件时先从腾讯 CDN 下载解密；</li>
 *     <li>其余文字、语音转写、图片和文件问题交给 {@link AiChatService}。</li>
 * </ol>
 */
@Service
public class ILinkReplyService {

    static final String VOICE_WITHOUT_TEXT_REPLY = "暂时无法识别，请改发文字";
    static final String TTS_UNAVAILABLE_SUFFIX = "\n\n语音文件暂时无法生成，已保留文字回答。";
    static final String UNSUPPORTED_REPLY = "暂时只支持文字、图片、文件、短视频和带转写文字的语音";
    static final String MIXED_VIDEO_ATTACHMENT_REPLY = "暂不支持在同一条消息中同时发送视频和图片或文件";
    static final String DEFAULT_IMAGE_PROMPT = "请描述这张图片";

    private static final Logger log = LoggerFactory.getLogger(ILinkReplyService.class);

    private final AiChatService aiChatService;
    private final ILinkMediaDownloader mediaDownloader;
    private final ILinkFileDownloader fileDownloader;
    private final AiImageGenerationService imageGenerationService;
    private final VideoAnalysisService videoAnalysisService;
    private final TextToSpeechService textToSpeechService;
    private final TtsVoiceSelectionService voiceSelectionService;
    private final FileSessionService fileSessions;
    private final FileInstructionService fileInstructionService;

    public ILinkReplyService(AiChatService aiChatService, ILinkMediaDownloader mediaDownloader,
                             ILinkFileDownloader fileDownloader,
                             AiImageGenerationService imageGenerationService,
                             VideoAnalysisService videoAnalysisService,
                             TextToSpeechService textToSpeechService,
                             TtsVoiceSelectionService voiceSelectionService,
                             FileSessionService fileSessions,
                             FileInstructionService fileInstructionService) {
        this.aiChatService = aiChatService;
        this.mediaDownloader = mediaDownloader;
        this.fileDownloader = fileDownloader;
        this.imageGenerationService = imageGenerationService;
        this.videoAnalysisService = videoAnalysisService;
        this.textToSpeechService = textToSpeechService;
        this.voiceSelectionService = voiceSelectionService;
        this.fileSessions = fileSessions;
        this.fileInstructionService = fileInstructionService;
    }

    public ILinkReply createReply(
            WeixinMessage message,
            List<MessageItem> items,
            ILinkRuntimeState.Snapshot status
    ) {
        // 第一步只整理 SDK item，不做网络请求：文字会合并，语音取微信已有转写，图片只记存在。
        ExtractedContent content = extract(items);

        // 复刻 TJY：文件只缓存 5 分钟，等待下一条普通话指令，不进入版本/撤销文档模式。
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


        // “生图：”是项目约定的强制入口，不依赖大模型先猜用户意图。
        if (content.isPlainTextOnly() && isImageGenerationRequest(content.prompt())) {
            AiImageGenerationService.Result result = imageGenerationService.generate(
                    message.fromUserId(), extractImagePrompt(content.prompt()));
            return result.hasImage()
                    ? new ILinkReply.Image(result.imageBytes())
                    : new ILinkReply.Text(result.errorMessage());
        }

        // “语音：”只接受手打文字，不让微信语音转写误触发输出语音模式。
        if (content.isPlainTextOnly() && isVoiceOutputRequest(content.prompt())) {
            String answer = aiChatService.answerForVoice(message.fromUserId(), extractVoiceOutputPrompt(content.prompt()));
            try {
                TtsVoiceSelectionService.VoiceOption voice = voiceSelectionService.current(message.fromUserId());
                return textToSpeechService.synthesize(answer, voice.modelId(), voice.voiceId())
                        .<ILinkReply>map(audio -> new ILinkReply.AudioFile(audio.fileName(), audio.bytes()))
                        .orElseGet(() -> new ILinkReply.Text(answer + TTS_UNAVAILABLE_SUFFIX));
            } catch (SpeechSynthesisException exception) {
                log.warn("Could not synthesize TTS reply, user={}, reason={}", anonymize(message.fromUserId()),
                        exception.getMessage());
                return new ILinkReply.Text(answer + "\n\n" + exception.userMessage());
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
         * 复刻 TJY TextMessageHandler：所有纯文本都经过同一份隐藏 FILE_GEN 提示。
         * 普通聊天返回文本；模型输出 FILE_GEN||JSON 时直接生成文件。
         */
        if (content.isPlainTextOnly()) {
            AiFile sourceFile = fileSessions.consume(message.fromUserId()).orElse(null);
            return toReply(fileInstructionService.process(
                    message.fromUserId(), content.prompt(), sourceFile));
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
        String answer = aiChatService.answer(message.fromUserId(), prompt, images);
        return new ILinkReply.Text(answer);
    }

    private static ILinkReply toReply(FileInstructionService.Result result) {
        return result.hasFile()
                ? new ILinkReply.DocumentFile(result.fileName(), result.bytes(), "文件已生成")
                : new ILinkReply.Text(result.text());
    }

    /**
     * 供 {@link ILinkBotService} 在真正处理回复之前选择线程池。这里只做同样的内容提取和
     * 格式判断，不调用图片服务，所以很快就能返回。
     */
    boolean isImageGenerationMessage(List<MessageItem> items) {
        ExtractedContent content = extract(items == null ? List.of() : items);
        return content.isPlainTextOnly() && isImageGenerationRequest(content.prompt());
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
        String voiceCommandReply = handleVoiceCommand(userId, command);
        if (voiceCommandReply != null) {
            return voiceCommandReply;
        }
        return switch (command) {
            case "帮助" -> "支持的功能：\n"
                    + "1. 发送普通文字与 AI 对话\n"
                    + "2. 发送图片，并附上你的问题\n"
                    + "3. 发送带微信转写文字的语音\n"
                    + "4. 发送“状态”查看运行状态\n"
                    + "5. 发送“清空”清除自己的上下文\n"
                    + "6. 发送“生图：画面描述”强制让 AI 生图\n"
                    + "7. 发送“语音：问题”获取 MP3 语音文件\n"
                    + "8. 发送“音色列表”查看可用音色\n"
                    + "9. 发送“设置音色：龙婉”实时切换音色\n"
                    + "10. 发送“当前音色”或“重置音色”\n"
                    + "11. 发送 60 秒以内的短视频进行画面与语音联合分析\n"
                    + "12. 发送文件后直接用普通话说明要求，例如“帮我把这个变成 PDF”\n"
                    + "13. 不上传文件也可直接说“写一份 Word 报告”或“生成 Excel 表格”";
            case "状态" -> "微信连接：" + status.connectionStatus() + "\n"
                    + "长轮询：" + (status.polling() ? "运行中" : "未运行") + "\n"
                    + "AI：" + (aiChatService.isEnabled() ? "已启用" : "未启用") + "\n"
                    + "模型：" + (aiChatService.isEnabled() ? aiChatService.model() : "-") + "\n"
                    + "当前音色：" + voiceSelectionService.current(userId).display();
            case "清空" -> {
                aiChatService.clear(userId);
                fileSessions.clear(userId);
                yield "已清空你的聊天记录";
            }
            default -> null;
        };
    }

    private String handleVoiceCommand(String userId, String command) {
        if ("音色列表".equals(command)) {
            return voiceSelectionService.listMessage(userId);
        }
        if ("当前音色".equals(command)) {
            return "当前音色：" + voiceSelectionService.current(userId).display();
        }
        if ("重置音色".equals(command) || "恢复默认音色".equals(command)) {
            return "已恢复默认音色：" + voiceSelectionService.reset(userId).display();
        }
        if (command.matches("^(设置|切换|更换|换)音色[：:].+")) {
            String requested = command.replaceFirst("^(设置|切换|更换|换)音色[：:]+", "").trim();
            return voiceSelectionService.select(userId, requested)
                    .map(option -> "已切换音色：" + option.display() + "。下一条“语音：问题”立即生效。")
                    .orElse("没有找到音色“" + requested + "”。发送“音色列表”查看可用音色。");
        }
        return null;
    }

    private static boolean isImageGenerationRequest(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        return text.matches("^生图[：:].+");
    }

    private static String extractImagePrompt(String prompt) {
        String text = prompt.trim();
        if (text.matches("^生图[：:].+")) {
            text = text.replaceFirst("^生图[：:]+", "").trim();
        }
        return text.isBlank() ? "一幅精美的自然风景" : text;
    }

    private static boolean isVoiceOutputRequest(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        return text.matches("^语音[：:].+");
    }

    private static String extractVoiceOutputPrompt(String prompt) {
        return prompt.trim().replaceFirst("^语音[：:]+", "").trim();
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
