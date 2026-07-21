package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.bot.audio.SpeechSynthesisException;
import com.example.ykdsummer.bot.audio.TextToSpeechService;
import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import com.example.ykdsummer.bot.document.DocumentEditException;
import com.example.ykdsummer.bot.document.DocumentEditService;
import com.example.ykdsummer.bot.document.DocumentAnalysisService;
import com.example.ykdsummer.bot.document.DocumentIntentRouter;
import com.example.ykdsummer.bot.document.DocumentGenerationService;
import com.example.ykdsummer.bot.document.DocumentTextExtractor;
import com.example.ykdsummer.bot.document.RecentDocumentContextService;
import com.example.ykdsummer.bot.document.DocumentSessionService;
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
    static final String DEFAULT_FILE_PROMPT = "请读取并总结这个文件";
    static final String DOCUMENT_HINT = "直接说你的要求即可；版本操作可发送：撤销、使用原版、完成、关闭文件。";

    private static final Logger log = LoggerFactory.getLogger(ILinkReplyService.class);

    private final AiChatService aiChatService;
    private final ILinkMediaDownloader mediaDownloader;
    private final ILinkFileDownloader fileDownloader;
    private final AiImageGenerationService imageGenerationService;
    private final VideoAnalysisService videoAnalysisService;
    private final TextToSpeechService textToSpeechService;
    private final TtsVoiceSelectionService voiceSelectionService;
    private final DocumentSessionService documentSessions;
    private final DocumentEditService documentEditService;
    private final DocumentAnalysisService documentAnalysisService;
    private final DocumentIntentRouter documentIntentRouter;
    private final DocumentGenerationService documentGenerationService;
    private final DocumentTextExtractor documentTextExtractor;
    private final RecentDocumentContextService recentDocumentContexts;

    public ILinkReplyService(AiChatService aiChatService, ILinkMediaDownloader mediaDownloader,
                             ILinkFileDownloader fileDownloader,
                             AiImageGenerationService imageGenerationService,
                             VideoAnalysisService videoAnalysisService,
                             TextToSpeechService textToSpeechService,
                             TtsVoiceSelectionService voiceSelectionService,
                             DocumentSessionService documentSessions,
                             DocumentEditService documentEditService,
                             DocumentAnalysisService documentAnalysisService,
                             DocumentIntentRouter documentIntentRouter,
                             DocumentGenerationService documentGenerationService,
                             DocumentTextExtractor documentTextExtractor,
                             RecentDocumentContextService recentDocumentContexts) {
        this.aiChatService = aiChatService;
        this.mediaDownloader = mediaDownloader;
        this.fileDownloader = fileDownloader;
        this.imageGenerationService = imageGenerationService;
        this.videoAnalysisService = videoAnalysisService;
        this.textToSpeechService = textToSpeechService;
        this.voiceSelectionService = voiceSelectionService;
        this.documentSessions = documentSessions;
        this.documentEditService = documentEditService;
        this.documentAnalysisService = documentAnalysisService;
        this.documentIntentRouter = documentIntentRouter;
        this.documentGenerationService = documentGenerationService;
        this.documentTextExtractor = documentTextExtractor;
        this.recentDocumentContexts = recentDocumentContexts;
    }

    public ILinkReply createReply(
            WeixinMessage message,
            List<MessageItem> items,
            ILinkRuntimeState.Snapshot status
    ) {
        // 第一步只整理 SDK item，不做网络请求：文字会合并，语音取微信已有转写，图片只记存在。
        ExtractedContent content = extract(items);

        // 手机端先单独发文件：保存原版并进入文档模式，下一条纯文字由本地规则区分分析或修改。
        if (content.hasFile()) {
            if (content.hasImage() || content.hasVideo()) {
                return new ILinkReply.Text("文档模式一次只能发送一个文件，不能同时附带图片或视频");
            }
            try {
                List<AiFile> uploaded = fileDownloader.downloadFiles(items);
                if (uploaded.size() != 1) {
                    return new ILinkReply.Text("文档模式一次只能发送一个文件");
                }
                DocumentSessionService.DocumentSnapshot snapshot = documentSessions.open(
                        message.fromUserId(), uploaded.getFirst());
                documentTextExtractor.extract(snapshot.extension(), uploaded.getFirst().bytes())
                        .ifPresent(text -> recentDocumentContexts.remember(
                                message.fromUserId(), snapshot.originalFileName(), text,
                                RecentDocumentContextService.Kind.SOURCE));
                return new ILinkReply.Text("已接收：" + snapshot.originalFileName() + "\n"
                        + "直接告诉我你想做什么，例如“总结重点”“把第二段改短”或“根据内容写一份建议 Word”。\n"
                        + DOCUMENT_HINT + simplifiedFormatNotice(snapshot.extension()));
            } catch (ILinkFileDownloader.FileProcessingException exception) {
                log.warn("Could not prepare iLink file, user={}, reason={}",
                        anonymize(message.fromUserId()), exception.userMessage());
                return new ILinkReply.Text(exception.userMessage());
            } catch (DocumentEditException exception) {
                return new ILinkReply.Text(exception.userMessage());
            }
        }

        // 文档模式只接管纯手打文字；语音转写、图片和视频仍走原来的多模态分支。
        if (content.isPlainTextOnly() && documentSessions.hasActive(message.fromUserId())) {
            return handleDocumentMode(message.fromUserId(), content.prompt());
        }

        // 固定命令只接受纯手打文字，避免语音转写为“帮助”时误触发本地命令。
        if (content.isPlainTextOnly()) {
            String commandReply = handleCommand(message.fromUserId(), content.prompt(), status);
            if (commandReply != null) {
                return new ILinkReply.Text(commandReply);
            }
        }

        /*
         * 已退出文档模式时，只有“明确生成意图 + 明确文件格式”同时存在才触发文件生成。
         * 这样“生成 PDF”会使用最近文档，而“生成一段介绍”仍是普通文本对话。
         */
        if (content.isPlainTextOnly() && isRecentDocumentGenerationRequest(content.prompt())) {
            return recentDocumentContexts.referenceForGeneration(message.fromUserId(), content.prompt())
                    .<ILinkReply>map(reference -> generateDocumentFromRecent(
                            message.fromUserId(), reference, content.prompt()))
                    .orElseGet(() -> new ILinkReply.Text(
                            "没有找到最近文档内容，请先发送一个文件。\n"
                                    + "以后退出文档模式后，可发送“生成 PDF：要求”或“生成 Word：要求”。"));
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
        if (!documentSessions.hasActive(message.fromUserId()) && images.isEmpty()) {
            prompt = recentDocumentContexts.augmentIfRelevant(message.fromUserId(), prompt);
        }
        String answer = aiChatService.answer(message.fromUserId(), prompt, images);
        return new ILinkReply.Text(answer);
    }

    private ILinkReply handleDocumentMode(String userId, String prompt) {
        String command = prompt.trim();
        try {
            if (isApplyLastAnalysisRequest(command)) {
                return applyLastAnalysis(userId);
            }
            return switch (command) {
                case "当前文件", "文件状态" -> documentSessions.current(userId)
                        .<ILinkReply>map(snapshot -> new ILinkReply.Text(documentStatus(snapshot)))
                        .orElseGet(() -> new ILinkReply.Text("当前没有正在处理的文件，请先发送一个文件"));
                case "撤销" -> documentSessions.undo(userId)
                        .<ILinkReply>map(snapshot -> documentReply(
                                documentSessions.currentFile(userId),
                                "已撤销到版本 v" + snapshot.currentVersion() + "，当前仍处于文档模式。"))
                        .orElseGet(() -> new ILinkReply.Text("当前已经是原版，无法继续撤销。"));
                case "使用原版", "回到原版" -> {
                    DocumentSessionService.DocumentSnapshot snapshot = documentSessions.useOriginal(userId);
                    yield documentReply(documentSessions.currentFile(userId),
                            "已切换到原版 v" + snapshot.currentVersion() + "，下一条文字将基于原版修改。");
                }
                case "完成", "使用当前", "确认当前" -> {
                    DocumentSessionService.VersionFile current = documentSessions.currentFile(userId);
                    documentSessions.close(userId);
                    yield new ILinkReply.DocumentFile(current.fileName(), current.bytes(),
                            "已确认当前版本并退出文档模式。后续普通文字恢复为正常对话。\n"
                                    + "如需继续修改，请重新发送文件。");
                }
                case "关闭文件", "退出文档模式" -> {
                    documentSessions.close(userId);
                    yield new ILinkReply.Text("已退出文档模式，已生成的文件和版本没有删除。后续普通文字恢复为正常对话。");
                }
                case "分析" -> documentSessions.consumePendingInstruction(userId)
                        .<ILinkReply>map(question -> analyzeDocument(userId, question))
                        .orElseGet(() -> new ILinkReply.Text("当前没有等待确认的内容，请直接告诉我你想分析什么。"));
                case "修改" -> documentSessions.consumePendingInstruction(userId)
                        .<ILinkReply>map(instruction -> editDocument(userId, instruction))
                        .orElseGet(() -> new ILinkReply.Text("当前没有等待确认的内容，请直接告诉我你想怎么修改。"));
                case "生成" -> documentSessions.consumePendingInstruction(userId)
                        .<ILinkReply>map(instruction -> generateDocument(userId, instruction))
                        .orElseGet(() -> new ILinkReply.Text("当前没有等待确认的内容，请直接告诉我想生成什么文件。"));
                default -> {
                    DocumentIntentRouter.Decision decision = documentIntentRouter.route(prompt);
                    yield switch (decision.intent()) {
                        case ANALYZE -> analyzeDocument(userId, decision.instruction());
                        case EDIT -> editDocument(userId, decision.instruction());
                        case GENERATE -> generateDocument(userId, decision.instruction());
                        case AMBIGUOUS -> {
                            documentSessions.savePendingInstruction(userId, decision.instruction());
                            yield new ILinkReply.Text("这句话还不够明确：你想分析、修改，还是另生成一个文件？\n"
                                    + "回复“分析”“修改”或“生成”即可；确认前文件不会改动。");
                        }
                    };
                }
            };
        } catch (DocumentEditException exception) {
            log.warn("Document mode failed, user={}, reason={}", anonymize(userId), exception.getMessage());
            return new ILinkReply.Text(exception.userMessage() + "\n当前文件仍可继续处理。");
        }
    }

    private ILinkReply applyLastAnalysis(String userId) {
        return documentSessions.lastAnalysis(userId)
                .<ILinkReply>map(analysis -> editDocument(userId,
                        "请依据下面最近一次分析建议修改当前文件，只应用与文件有关且明确可执行的建议：\n" + analysis))
                .orElseGet(() -> new ILinkReply.Text("当前还没有分析建议，请先直接告诉我你想分析什么。"));
    }

    private ILinkReply analyzeDocument(String userId, String question) {
        String answer = documentAnalysisService.analyze(userId, question);
        return new ILinkReply.Text(answer + "\n\n文件未修改；若认可这些建议，回复“应用建议”即可。");
    }

    private ILinkReply editDocument(String userId, String instruction) {
        DocumentEditService.EditResult result = documentEditService.edit(userId, instruction);
        documentTextExtractor.extract(result.snapshot().extension(), result.bytes())
                .ifPresent(text -> recentDocumentContexts.remember(
                        userId, result.fileName(), text, RecentDocumentContextService.Kind.MODIFIED));
        String warning = result.warning().isBlank() ? "" : "\n注意：" + result.warning();
        return new ILinkReply.DocumentFile(result.fileName(), result.bytes(),
                "已修改并生成 v" + result.snapshot().currentVersion() + "。"
                        + warning + "\n可继续直接说修改要求；不满意发“撤销”，满意发“完成”。");
    }

    private ILinkReply generateDocument(String userId, String instruction) {
        DocumentGenerationService.GenerationResult result = documentGenerationService.generate(userId, instruction);
        recentDocumentContexts.remember(userId, result.fileName(), result.extractedContent(),
                RecentDocumentContextService.Kind.GENERATED);
        String warning = result.warning().isBlank() ? "" : "\n注意：" + result.warning();
        return new ILinkReply.DocumentFile(result.fileName(), result.bytes(),
                "已参考当前文件生成独立的 "
                        + result.extension().toUpperCase(java.util.Locale.ROOT) + " 文件。\n"
                        + "原文件和当前版本 v" + result.sourceSnapshot().currentVersion() + " 没有修改。"
                        + warning + "\n可继续直接说下一步要求。");
    }

    private ILinkReply generateDocumentFromRecent(
            String userId,
            RecentDocumentContextService.RecentDocument reference,
            String instruction
    ) {
        try {
            DocumentGenerationService.GenerationResult result =
                    documentGenerationService.generateFromRecent(reference, instruction);
            recentDocumentContexts.remember(
                    userId, result.fileName(), result.extractedContent(),
                    RecentDocumentContextService.Kind.GENERATED);
            String warning = result.warning().isBlank() ? "" : "\n注意：" + result.warning();
            return new ILinkReply.DocumentFile(result.fileName(), result.bytes(),
                    "已根据最近文档上下文生成独立的 "
                            + result.extension().toUpperCase(java.util.Locale.ROOT) + " 文件。"
                            + warning + "\n当前仍是普通对话模式；可继续发送“生成 + 文件格式 + 要求”。");
        } catch (DocumentEditException exception) {
            log.warn("Recent document generation failed, reason={}", exception.getMessage());
            return new ILinkReply.Text(exception.userMessage());
        }
    }

    private ILinkReply.DocumentFile documentReply(
            DocumentSessionService.VersionFile file,
            String message
    ) {
        return new ILinkReply.DocumentFile(file.fileName(), file.bytes(), message + "\n" + DOCUMENT_HINT);
    }

    private static String documentStatus(DocumentSessionService.DocumentSnapshot snapshot) {
        return "当前文件：" + snapshot.originalFileName() + "\n"
                + "当前版本：v" + snapshot.currentVersion() + "\n"
                + "已生成版本数：" + snapshot.versionCount() + "\n"
                + DOCUMENT_HINT;
    }

    private static String simplifiedFormatNotice(String extension) {
        return switch (extension) {
            case "pdf" -> "\n\n注意：PDF 修改仍采用内容级重建，复杂排版和图片可能无法原样保留。";
            case "doc" -> "\n\n注意：旧版 .doc 目前只能分析；如需修改，请先转换为 .docx。";
            case "docx", "xlsx", "pptx" -> "\n\nOffice 修改会保留未涉及的结构；被修改的段落或文本框局部样式可能变化。";
            default -> "";
        };
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
                    + "12. 发送 TXT、MD、JSON、CSV、HTML、XML、Java、PDF、DOC、DOCX、XLSX 或 PPTX 文件进入文档模式\n"
                    + "13. 上传文件后直接说“总结重点”“把第二段改短”“写一份建议 Word”即可；分析/修改/生成前缀也兼容\n"
                    + "14. 退出后可问“刚才的文档讲了什么”，或发“生成 PDF/Word/PPT/Excel：要求”直接生成文件\n"
                    + "15. 文档模式还可用“应用建议、当前文件、撤销、使用原版、完成、关闭文件”";
            case "状态" -> "微信连接：" + status.connectionStatus() + "\n"
                    + "长轮询：" + (status.polling() ? "运行中" : "未运行") + "\n"
                    + "AI：" + (aiChatService.isEnabled() ? "已启用" : "未启用") + "\n"
                    + "模型：" + (aiChatService.isEnabled() ? aiChatService.model() : "-") + "\n"
                    + "当前音色：" + voiceSelectionService.current(userId).display();
            case "清空" -> {
                aiChatService.clear(userId);
                recentDocumentContexts.clear(userId);
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

    private static boolean isRecentDocumentGenerationRequest(String prompt) {
        String text = prompt == null ? "" : prompt.trim().toLowerCase(java.util.Locale.ROOT);
        boolean explicitGeneration = text.matches(
                "^(请|帮我|给我|替我|请帮我|请给我|麻烦)?(再|重新|另外|直接)?生成.*")
                || text.matches("^文件生成[：:].*")
                || text.matches("^(把|将|根据).+生成.*");
        boolean explicitFormat = text.matches(
                ".*(pdf|word|docx|ppt|pptx|演示文稿|幻灯片|excel|xlsx|电子表格|"
                        + "txt|纯文本|markdown|md文件|md格式|json|csv|html|xml).*"
        );
        return explicitGeneration && explicitFormat;
    }

    private static boolean isApplyLastAnalysisRequest(String prompt) {
        String text = prompt == null ? "" : prompt.strip();
        if (List.of("应用建议", "按建议修改", "按这些建议修改", "按刚才的建议修改",
                "按照建议修改", "采纳建议", "采用建议").contains(text)) {
            return true;
        }
        boolean referencesRecentAnalysis = List.of(
                "这些建议", "上述建议", "上面的建议", "刚才的建议", "你的建议",
                "刚才的分析", "分析结果").stream().anyMatch(text::contains);
        boolean asksToApply = List.of(
                "应用", "采用", "采纳", "按", "按照", "修改", "改一下", "调整", "优化"
        ).stream().anyMatch(text::contains);
        return referencesRecentAnalysis && asksToApply;
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
