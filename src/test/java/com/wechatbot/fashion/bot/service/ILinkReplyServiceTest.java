package com.wechatbot.fashion.bot.service;

import com.wechatbot.fashion.ai.model.AiFile;
import com.wechatbot.fashion.ai.model.AiImage;
import com.wechatbot.fashion.ai.service.AiChatService;
import com.wechatbot.fashion.ai.service.LocalImageAssetStore;
import com.wechatbot.fashion.bot.audio.TtsVoiceSelectionService;
import com.wechatbot.fashion.bot.config.LongTextOutputProperties;
import com.wechatbot.fashion.bot.file.FileInstructionService;
import com.wechatbot.fashion.bot.file.FileSessionService;
import com.wechatbot.fashion.bot.runtime.ILinkRuntimeState;
import com.wechatbot.fashion.bot.video.VideoAnalysisService;
import io.github.morningwn.protocol.FileItem;
import io.github.morningwn.protocol.ImageItem;
import io.github.morningwn.protocol.MessageItem;
import io.github.morningwn.protocol.ProtocolValues;
import io.github.morningwn.protocol.TextItem;
import io.github.morningwn.protocol.VoiceItem;
import io.github.morningwn.protocol.VideoItem;
import io.github.morningwn.protocol.WeixinMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ILinkReplyServiceTest {

    private AiChatService aiChatService;
    private ILinkMediaDownloader mediaDownloader;
    private ILinkFileDownloader fileDownloader;
    private ILinkReplyService replyService;
    private VideoAnalysisService videoAnalysisService;
    private TtsVoiceSelectionService voiceSelectionService;
    private FileSessionService fileSessions;
    private FileInstructionService fileInstructionService;
    private LocalImageAssetStore imageAssets;

    @BeforeEach
    void setUp() {
        aiChatService = mock(AiChatService.class);
        mediaDownloader = mock(ILinkMediaDownloader.class);
        fileDownloader = mock(ILinkFileDownloader.class);
        videoAnalysisService = mock(VideoAnalysisService.class);
        voiceSelectionService = mock(TtsVoiceSelectionService.class);
        fileSessions = mock(FileSessionService.class);
        fileInstructionService = mock(FileInstructionService.class);
        imageAssets = mock(LocalImageAssetStore.class);
        replyService = new ILinkReplyService(
                aiChatService, mediaDownloader, fileDownloader, videoAnalysisService,
                voiceSelectionService, fileSessions, fileInstructionService, imageAssets);
        when(aiChatService.isEnabled()).thenReturn(true);
        when(aiChatService.model()).thenReturn("gpt-5.6");
        when(fileSessions.consume(any())).thenReturn(Optional.empty());
        when(imageAssets.saveIncoming(any(), any(), any(), any())).thenReturn(
                new LocalImageAssetStore.StoredImage(
                        "img_testimage1", 1, java.nio.file.Path.of("mock-image.png"), "", "",
                        Instant.EPOCH, "image/png", "uploaded", ""
                )
        );
        when(voiceSelectionService.current(any())).thenReturn(
                voice("龙安洋", "longanyang", "阳光大男孩"));
    }

    @Test
    void fixedCommandsDoNotCallTheModel() {
        assertThat(textValue(replyService.createReply(message("user-a"), List.of(text("帮助")), status())))
                .contains("支持的功能");
        assertThat(textValue(replyService.createReply(message("user-a"), List.of(text("状态")), status())))
                .contains("微信连接：CONNECTED", "模型：gpt-5.6");
        assertThat(textValue(replyService.createReply(message("user-a"), List.of(text("清空")), status())))
                .contains("已清空你的聊天记录", "临时会话缓存", "未删除");

        verify(aiChatService).clear("user-a");
        verify(fileSessions).clear("user-a");
        verify(imageAssets).clearCurrent("user-a");
        verify(fileInstructionService).clearCurrentDocumentPointer("user-a");
        verify(aiChatService, never()).answer(any(), any(), any());
        verify(mediaDownloader, never()).downloadImages(any());
    }

    @Test
    void joinsAllTextItemsAndSendsTheCompleteQuestion() {
        when(fileInstructionService.process("user", "第一段\n第二段", null))
                .thenReturn(FileInstructionService.Result.text("模型回答"));

        ILinkReply reply = replyService.createReply(
                message("user"),
                List.of(text("第一段"), text("第二段")),
                status()
        );

        assertThat(textValue(reply)).isEqualTo("模型回答");
        verify(fileInstructionService).process("user", "第一段\n第二段", null);
    }

    @Test
    void usesWechatVoiceTranscriptAndRejectsVoiceWithoutTranscript() {
        when(aiChatService.answer(eq("user"), eq("语音内容"), eq(List.of())))
                .thenReturn("语音回答");

        assertThat(textValue(replyService.createReply(message("user"), List.of(voice("语音内容")), status())))
                .isEqualTo("语音回答");
        assertThat(textValue(replyService.createReply(message("user"), List.of(voice(null)), status())))
                .isEqualTo(ILinkReplyService.VOICE_WITHOUT_TEXT_REPLY);
    }

    @Test
    void incomingVoiceTranscriptNeverTriggersVoiceOrImageCommand() {
        when(aiChatService.answer("user", "语音：介绍一下 iLink", List.of())).thenReturn("普通文字回答");

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(voice("语音：介绍一下 iLink")), status());

        assertThat(textValue(reply)).isEqualTo("普通文字回答");
        verify(fileInstructionService, never()).process(any(), any(), any());
    }

    @Test
    void imageOnlyUsesDefaultPromptAndImageWithHelpTextIsNotACommand() {
        AiImage image = new AiImage("image/png", new byte[]{1, 2, 3});
        MessageItem imageItem = imageItem();
        when(mediaDownloader.downloadImages(any())).thenReturn(List.of(image));
        when(aiChatService.answerWithInternalPromptRich(eq("user"), eq(ILinkReplyService.DEFAULT_IMAGE_PROMPT),
                any(), eq(List.of())))
                .thenReturn(AiChatService.AssistantAnswer.text("图片说明"));
        when(aiChatService.answerWithInternalPromptRich(eq("user"), eq("帮助"), any(), eq(List.of())))
                .thenReturn(AiChatService.AssistantAnswer.text("图片问题回答"));

        assertThat(textValue(replyService.createReply(message("user"), List.of(imageItem), status())))
                .isEqualTo("图片说明");
        assertThat(textValue(replyService.createReply(message("user"), List.of(text("帮助"), imageItem), status())))
                .isEqualTo("图片问题回答");
    }

    @Test
    void naturalLanguageImageRequestAlwaysUsesTheAgentPath() {
        when(fileInstructionService.process("user", "给我生成一张小花的图片", null))
                .thenReturn(FileInstructionService.Result.text("Agent 会决定是否调用生图工具"));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("给我生成一张小花的图片")), status());

        assertThat(textValue(reply)).isEqualTo("Agent 会决定是否调用生图工具");
    }

    @Test
    void agentImageResultBecomesAnILinkImageReply() {
        byte[] png = {5, 4, 3};
        when(fileInstructionService.process("user", "给我生成一张小狗图片", null))
                .thenReturn(FileInstructionService.Result.image("图片已生成", png));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("给我生成一张小狗图片")), status());

        assertThat(reply).isInstanceOf(ILinkReply.Image.class);
        ILinkReply.Image image = (ILinkReply.Image) reply;
        assertThat(image.bytes()).containsExactly(png);
        assertThat(image.followUpText()).isEqualTo("图片已生成");
    }

    @Test
    void multipleAgentImagesBecomeAnIndependentWechatImageBatch() {
        when(fileInstructionService.process("user", "展示衣橱", null))
                .thenReturn(com.wechatbot.fashion.bot.file.FileInstructionService.Result.images(
                        "已发两页衣橱图", List.of(new byte[]{1}, new byte[]{2})));

        ILinkReply reply = replyService.createReply(message("user"), List.of(text("展示衣橱")), status());

        assertThat(reply).isInstanceOf(ILinkReply.ImageBatch.class);
        ILinkReply.ImageBatch batch = (ILinkReply.ImageBatch) reply;
        assertThat(batch.images()).containsExactly(new byte[]{1}, new byte[]{2});
        assertThat(batch.followUpText()).isEqualTo("已发两页衣橱图");
    }

    @Test
    void schedulerDoesNotGuessImageIntentBeforeTheAgentPlans() {
        assertThat(replyService.isImageGenerationMessage(List.of(text("生图：一只小猫")))).isFalse();
        assertThat(replyService.isImageGenerationMessage(List.of(text("你好")))).isFalse();
        assertThat(replyService.isImageGenerationMessage(List.of(text("给我生成图片")))).isFalse();
    }

    @Test
    void videoUsesIndependentAnalysisBranchAndCanCarryText() {
        MessageItem video = videoItem();
        List<MessageItem> items = List.of(text("视频里发生了什么"), video);
        when(videoAnalysisService.analyze("user", "视频里发生了什么", items)).thenReturn("视频分析结果");

        ILinkReply reply = replyService.createReply(message("user"), items, status());

        assertThat(textValue(reply)).isEqualTo("视频分析结果");
        assertThat(replyService.isVideoMessage(items)).isTrue();
        verify(videoAnalysisService).analyze("user", "视频里发生了什么", items);
        verify(aiChatService, never()).answer(any(), any(), any());
    }

    @Test
    void videoAndImageTogetherAreRejectedBeforeDownloading() {
        ILinkReply reply = replyService.createReply(
                message("user"), List.of(videoItem(), imageItem()), status());

        assertThat(textValue(reply)).isEqualTo(ILinkReplyService.MIXED_VIDEO_ATTACHMENT_REPLY);
        verify(videoAnalysisService, never()).analyze(any(), any(), any());
        verify(mediaDownloader, never()).downloadImages(any());
    }

    @Test
    void fileOnlyIsCachedForTheNextNaturalLanguageInstruction() {
        AiFile file = new AiFile("report.pdf", "application/pdf", new byte[]{1, 2, 3});
        MessageItem fileItem = fileItem();
        when(fileDownloader.downloadFiles(any())).thenReturn(List.of(file));

        ILinkReply reply = replyService.createReply(message("user"), List.of(fileItem), status());

        assertThat(textValue(reply)).isEqualTo("已收到文件，请告诉我怎么处理");
        verify(fileSessions).cache("user", file);
        verify(fileInstructionService, never()).process(any(), any(), any());
        verify(mediaDownloader, never()).downloadImages(any());
    }

    @Test
    void uploadedWordAndNaturalLanguageInstructionReturnPdf() {
        AiFile source = new AiFile("source.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[]{1, 2});
        byte[] pdf = new byte[]{3, 4, 5};
        when(fileSessions.consume("user")).thenReturn(Optional.of(source));
        when(fileInstructionService.process("user", "帮我把这个变成 PDF", source))
                .thenReturn(FileInstructionService.Result.file("output.pdf", pdf));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("帮我把这个变成 PDF")), status());

        assertThat(reply).isInstanceOf(ILinkReply.DocumentFile.class);
        ILinkReply.DocumentFile file = (ILinkReply.DocumentFile) reply;
        assertThat(file.fileName()).isEqualTo("output.pdf");
        assertThat(file.bytes()).containsExactly(pdf);
        verify(fileSessions).consume("user");
        verify(fileInstructionService).process("user", "帮我把这个变成 PDF", source);
    }

    @Test
    void naturalLanguageCanGenerateWordWithoutUploadingAFile() {
        byte[] docx = new byte[]{6, 7, 8};
        when(fileInstructionService.process("user", "帮我写一份 Word 周报", null))
                .thenReturn(FileInstructionService.Result.file("output.docx", docx));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("帮我写一份 Word 周报")), status());

        assertThat(reply).isInstanceOf(ILinkReply.DocumentFile.class);
        ILinkReply.DocumentFile file = (ILinkReply.DocumentFile) reply;
        assertThat(file.fileName()).isEqualTo("output.docx");
        assertThat(file.bytes()).containsExactly(docx);
        verify(fileInstructionService).process("user", "帮我写一份 Word 周报", null);
    }

    @Test
    void asksBeforeSendingAnOversizedReplyAndSendsTxtAfterSelection() {
        LongTextOutputProperties properties = new LongTextOutputProperties();
        properties.setThresholdCharacters(200);
        replyService = new ILinkReplyService(
                aiChatService, mediaDownloader, fileDownloader, videoAnalysisService,
                voiceSelectionService, fileSessions, fileInstructionService, imageAssets,
                new LongTextOutputService(properties));
        String longText = "天气详情".repeat(200);
        when(fileInstructionService.process("user", "查询全部天气", null))
                .thenReturn(FileInstructionService.Result.text(longText));

        ILinkReply offer = replyService.createReply(message("user"), List.of(text("查询全部天气")), status());
        ILinkReply selected = replyService.createReply(message("user"), List.of(text("TXT")), status());

        assertThat(textValue(offer)).contains("回复“全文”", "回复“TXT”");
        assertThat(selected).isInstanceOf(ILinkReply.DocumentFile.class);
        ILinkReply.DocumentFile txt = (ILinkReply.DocumentFile) selected;
        assertThat(txt.fileName()).isEqualTo("查询结果.txt");
        assertThat(new String(txt.bytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(longText);
        verify(fileInstructionService, never()).process("user", "TXT", null);
    }

    private static String textValue(ILinkReply reply) {
        return ((ILinkReply.Text) reply).value();
    }

    private static TtsVoiceSelectionService.VoiceOption voice(
            String displayName, String voiceId, String description
    ) {
        return new TtsVoiceSelectionService.VoiceOption(
                displayName, "cosyvoice-v3-flash", voiceId, description, List.of());
    }

    private static ILinkRuntimeState.Snapshot status() {
        return new ILinkRuntimeState.Snapshot(
                true, true, "CONNECTED", null, "account", 1, 1,
                "TEXT", Instant.now(), null
        );
    }

    private static WeixinMessage message(String userId) {
        return new WeixinMessage(
                1L, 1L, userId, "bot", "client", System.currentTimeMillis(), null, null,
                null, null, ProtocolValues.MESSAGE_TYPE_USER, ProtocolValues.MESSAGE_STATE_FINISH,
                List.of(), "context"
        );
    }

    private static MessageItem text(String value) {
        return new MessageItem(
                ProtocolValues.ITEM_TYPE_TEXT, null, null, true, null, null,
                new TextItem(value), null, null, null, null
        );
    }

    private static MessageItem voice(String transcript) {
        return new MessageItem(
                ProtocolValues.ITEM_TYPE_VOICE, null, null, true, null, null,
                null, null, new VoiceItem(null, null, null, null, 1000L, transcript), null, null
        );
    }

    private static MessageItem imageItem() {
        return new MessageItem(
                ProtocolValues.ITEM_TYPE_IMAGE, null, null, true, null, null,
                null, new ImageItem(null, null, null, null, null, null, null, null, null),
                null, null, null
        );
    }

    private static MessageItem videoItem() {
        return new MessageItem(
                ProtocolValues.ITEM_TYPE_VIDEO, null, null, true, null, null,
                null, null, null, null,
                new VideoItem(null, null, null, null, null, null, null, null)
        );
    }

    private static MessageItem fileItem() {
        return new MessageItem(
                ProtocolValues.ITEM_TYPE_FILE, null, null, true, null, null,
                null, null, null, new FileItem(null, "report.pdf", null, "3"), null
        );
    }
}
