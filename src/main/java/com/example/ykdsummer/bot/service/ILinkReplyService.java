package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.orchestration.AgentCoordinator;
import com.example.ykdsummer.bot.file.FileSessionService;
import com.example.ykdsummer.bot.message.CommandHandler;
import com.example.ykdsummer.bot.message.MessageExtractor;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import com.example.ykdsummer.bot.video.VideoAnalysisService;
import io.github.morningwn.protocol.MessageItem;
import io.github.morningwn.protocol.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 微信消息路由层。根据 {@link MessageExtractor} 提取的内容，决定走哪个服务分支。
 *
 * <p>本层负责"决策"，具体能力由各自的 Service / Tool 完成。</p>
 */
@Service
public class ILinkReplyService {

    static final String VOICE_WITHOUT_TEXT_REPLY = "暂时无法识别，请改发文字";
    static final String UNSUPPORTED_REPLY = "暂时只支持文字、图片、文件、短视频和带转写文字的语音";
    static final String MIXED_VIDEO_ATTACHMENT_REPLY = "暂不支持在同一条消息中同时发送视频和图片或文件";
    static final String DEFAULT_IMAGE_PROMPT = "请描述这张图片";

    private static final Logger log = LoggerFactory.getLogger(ILinkReplyService.class);

    private final MessageExtractor messageExtractor;
    private final CommandHandler commandHandler;
    private final ILinkMediaDownloader mediaDownloader;
    private final ILinkFileDownloader fileDownloader;
    private final VideoAnalysisService videoAnalysisService;
    private final FileSessionService fileSessions;
    private final AgentCoordinator agentCoordinator;

    public ILinkReplyService(
            MessageExtractor messageExtractor,
            CommandHandler commandHandler,
            ILinkMediaDownloader mediaDownloader,
            ILinkFileDownloader fileDownloader,
            VideoAnalysisService videoAnalysisService,
            FileSessionService fileSessions,
            AgentCoordinator agentCoordinator
    ) {
        this.messageExtractor = messageExtractor;
        this.commandHandler = commandHandler;
        this.mediaDownloader = mediaDownloader;
        this.fileDownloader = fileDownloader;
        this.videoAnalysisService = videoAnalysisService;
        this.fileSessions = fileSessions;
        this.agentCoordinator = agentCoordinator;
    }

    /**
     * 路由入口。根据消息内容选择对应的服务处理。
     */
    public ILinkReply createReply(
            WeixinMessage message,
            List<MessageItem> items,
            ILinkRuntimeState.Snapshot status
    ) {
        MessageExtractor.ExtractedContent content = messageExtractor.extract(items);

        // 固定命令分支（纯手打文字）
        if (content.isPlainTextOnly()) {
            String commandReply = commandHandler.handle(
                    message.fromUserId(), content.prompt(), status);
            if (commandReply != null) {
                return new ILinkReply.Text(commandReply);
            }
        }

        // 空白或无法识别
        if (content.prompt().isBlank() && !content.hasImage() && !content.hasFile() && !content.hasVideo()) {
            return new ILinkReply.Text(
                    content.hasVoiceWithoutTranscript() ? VOICE_WITHOUT_TEXT_REPLY : UNSUPPORTED_REPLY);
        }

        // 视频分析（独立分支，不走 Agent）
        if (content.hasVideo()) {
            return handleVideo(message, content, items);
        }

        // 文件分支：缓存、立即处理或走 Agent
        if (content.hasFile()) {
            return handleFile(message, content, items);
        }

        // 所有剩余消息（纯文本、语音转写、图片 ± 文字）统一走 Agent 编排
        return handleAgentRequest(message, content, items);
    }

    // ======================== 分支方法 ========================

    private ILinkReply handleFile(WeixinMessage message, MessageExtractor.ExtractedContent content,
                                  List<MessageItem> items) {
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
                String prompt = "[文件：" + file.fileName() + "]\n" + content.prompt();
                return toReply(agentCoordinator.execute(
                        message.fromUserId(), prompt, List.of(), file));
            }
            fileSessions.cache(message.fromUserId(), file);
            return new ILinkReply.Text("已收到文件，请告诉我怎么处理");
        } catch (ILinkFileDownloader.FileProcessingException exception) {
            log.warn("Could not prepare iLink file, user={}, reason={}",
                    anonymize(message.fromUserId()), exception.userMessage());
            return new ILinkReply.Text(exception.userMessage());
        }
    }

    private ILinkReply handleVideo(WeixinMessage message, MessageExtractor.ExtractedContent content,
                                   List<MessageItem> items) {
        if (content.hasImage() || content.hasFile()) {
            return new ILinkReply.Text(MIXED_VIDEO_ATTACHMENT_REPLY);
        }
        return new ILinkReply.Text(videoAnalysisService.analyze(
                message.fromUserId(), content.prompt(), items));
    }

    private ILinkReply handleAgentRequest(WeixinMessage message, MessageExtractor.ExtractedContent content,
                                          List<MessageItem> items) {
        String userId = message.fromUserId();
        List<AiImage> images;
        try {
            images = content.hasImage() ? mediaDownloader.downloadImages(items) : List.of();
        } catch (ILinkMediaDownloader.MediaProcessingException exception) {
            log.warn("Could not prepare iLink image, user={}, reason={}",
                    anonymize(message.fromUserId()), exception.userMessage());
            return new ILinkReply.Text(exception.userMessage());
        }
        String prompt = content.prompt().isBlank() ? DEFAULT_IMAGE_PROMPT : content.prompt();
        // B3: if there is a file from an earlier session, prepend file name info
        AiFile sourceFile = fileSessions.consume(userId).orElse(null);
        if (sourceFile != null) {
            prompt = "[文件：" + sourceFile.fileName() + "]\n" + prompt;
        }
        return toReply(agentCoordinator.execute(userId, prompt, images, sourceFile));
    }



    // ======================== 工具方法 ========================

    private static ILinkReply toReply(AgentCoordinator.AgentResult result) {
        if (result.hasImage()) {
            return new ILinkReply.Image(result.bytes());
        }
        if (result.hasAudio()) {
            return new ILinkReply.AudioFile(result.fileName(), result.bytes());
        }
        if (result.hasFile()) {
            // 有文字回复时只发文字（一段回复），不发送文件，避免两段回复
            if (result.text() != null && !result.text().isBlank()) {
                return new ILinkReply.Text(result.text());
            }
            // 只有文件没有文字时，发文件，跟进文字为空（processReply 不会额外发文字）
            return new ILinkReply.DocumentFile(result.fileName(), result.bytes(), "");
        }
        return new ILinkReply.Text(result.text());
    }



    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
