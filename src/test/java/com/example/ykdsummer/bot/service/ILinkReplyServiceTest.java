package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.orchestration.AgentCoordinator;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.bot.audio.TextToSpeechService;
import com.example.ykdsummer.bot.message.CommandHandler;
import com.example.ykdsummer.bot.message.MessageExtractor;
import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import com.example.ykdsummer.bot.file.FileSessionService;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import com.example.ykdsummer.bot.video.VideoAnalysisService;
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
    private AgentCoordinator agentCoordinator;
    private VideoAnalysisService videoAnalysisService;
    private TextToSpeechService textToSpeechService;
    private TtsVoiceSelectionService voiceSelectionService;
    private FileSessionService fileSessions;

    @BeforeEach
    void setUp() {
        aiChatService = mock(AiChatService.class);
        mediaDownloader = mock(ILinkMediaDownloader.class);
        fileDownloader = mock(ILinkFileDownloader.class);
        agentCoordinator = mock(AgentCoordinator.class);
        videoAnalysisService = mock(VideoAnalysisService.class);
        textToSpeechService = mock(TextToSpeechService.class);
        voiceSelectionService = mock(TtsVoiceSelectionService.class);
        fileSessions = mock(FileSessionService.class);
        replyService = new ILinkReplyService(
                mock(MessageExtractor.class), mock(CommandHandler.class),
                aiChatService, mediaDownloader, fileDownloader, videoAnalysisService,
                textToSpeechService, voiceSelectionService, fileSessions, agentCoordinator);
        when(aiChatService.isEnabled()).thenReturn(true);
        when(aiChatService.model()).thenReturn("gpt-5.6");
        when(fileSessions.consume(any())).thenReturn(Optional.empty());
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
                .isEqualTo("已清空你的聊天记录");

        verify(aiChatService).clear("user-a");
        verify(fileSessions).clear("user-a");
        verify(aiChatService, never()).answer(any(), any(), any());
        verify(mediaDownloader, never()).downloadImages(any());
    }

    @Test
    void joinsAllTextItemsAndSendsTheCompleteQuestion() {
        when(agentCoordinator.execute("user", "第一段\n第二段", null))
                .thenReturn(AgentCoordinator.AgentResult.text("模型回答"));

        ILinkReply reply = replyService.createReply(
                message("user"),
                List.of(text("第一段"), text("第二段")),
                status()
        );

        assertThat(textValue(reply)).isEqualTo("模型回答");
        verify(agentCoordinator).execute("user", "第一段\n第二段", null);
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
    void typedVoicePrefixGeneratesAnMp3FileReply() {
        byte[] mp3 = {0x49, 0x44, 0x33, 1, 2, 3};
        when(aiChatService.answerForVoice("user", "介绍一下 iLink")).thenReturn("这是语音回答");
        when(voiceSelectionService.current("user")).thenReturn(voice("龙婉", "longwan_v3", "细腻柔声女"));
        when(textToSpeechService.synthesize("这是语音回答", "cosyvoice-v3-flash", "longwan_v3"))
                .thenReturn(Optional.of(new TextToSpeechService.SynthesizedAudio("answer.mp3", mp3)));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("语音：介绍一下 iLink")), status());

        assertThat(reply).isInstanceOf(ILinkReply.AudioFile.class);
        ILinkReply.AudioFile audio = (ILinkReply.AudioFile) reply;
        assertThat(audio.fileName()).isEqualTo("answer.mp3");
        assertThat(audio.bytes()).containsExactly(mp3);
        verify(aiChatService).answerForVoice("user", "介绍一下 iLink");
        verify(textToSpeechService).synthesize("这是语音回答", "cosyvoice-v3-flash", "longwan_v3");
    }

    @Test
    void voiceCommandsListSelectShowAndResetWithoutCallingTheModel() {
        TtsVoiceSelectionService.VoiceOption selected = voice("龙婉", "longwan_v3", "细腻柔声女");
        when(voiceSelectionService.listMessage("user")).thenReturn("可用音色：龙安洋、龙安欢、龙婉、龙老铁");
        when(voiceSelectionService.select("user", "龙婉")).thenReturn(Optional.of(selected));
        when(voiceSelectionService.current("user")).thenReturn(selected);
        when(voiceSelectionService.reset("user")).thenReturn(voice("龙安洋", "longanyang", "阳光大男孩"));

        assertThat(textValue(replyService.createReply(message("user"), List.of(text("音色列表")), status())))
                .contains("龙安洋", "龙安欢", "龙婉", "龙老铁");
        assertThat(textValue(replyService.createReply(message("user"), List.of(text("设置音色：龙婉")), status())))
                .contains("已切换音色：龙婉", "立即生效");
        assertThat(textValue(replyService.createReply(message("user"), List.of(text("当前音色")), status())))
                .contains("当前音色：龙婉");
        assertThat(textValue(replyService.createReply(message("user"), List.of(text("重置音色")), status())))
                .contains("已恢复默认音色：龙安洋");

        verify(aiChatService, never()).answer(any(), any(), any());
        verify(aiChatService, never()).answerForVoice(any(), any());
    }

    @Test
    void incomingVoiceTranscriptNeverTriggersVoiceOrImageCommand() {
        when(aiChatService.answer("user", "语音：介绍一下 iLink", List.of())).thenReturn("普通文字回答");

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(voice("语音：介绍一下 iLink")), status());

        assertThat(textValue(reply)).isEqualTo("普通文字回答");
        verify(aiChatService, never()).answerForVoice(any(), any());
        verify(textToSpeechService, never()).synthesize(any(), any(), any());
    }

    @Test
    void imageOnlyUsesDefaultPromptAndImageWithHelpTextIsNotACommand() {
        AiImage image = new AiImage("image/png", new byte[]{1, 2, 3});
        MessageItem imageItem = imageItem();
        when(mediaDownloader.downloadImages(any())).thenReturn(List.of(image));
        when(aiChatService.answer("user", ILinkReplyService.DEFAULT_IMAGE_PROMPT, List.of(image)))
                .thenReturn("图片说明");
        when(aiChatService.answer("user", "帮助", List.of(image))).thenReturn("图片问题回答");

        assertThat(textValue(replyService.createReply(message("user"), List.of(imageItem), status())))
                .isEqualTo("图片说明");
        assertThat(textValue(replyService.createReply(message("user"), List.of(text("帮助"), imageItem), status())))
                .isEqualTo("图片问题回答");
    }

    @Test
    void imageGenerationRequestReturnsImageReply() {
        byte[] bytes = {1, 2, 3};
        when(agentCoordinator.execute("user", "生图：一张小花的图片", null))
                .thenReturn(AgentCoordinator.AgentResult.image(bytes));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("生图：一张小花的图片")), status());

        assertThat(reply).isInstanceOf(ILinkReply.Image.class);
        assertThat(((ILinkReply.Image) reply).bytes()).containsExactly(bytes);
        verify(agentCoordinator).execute("user", "生图：一张小花的图片", null);
    }

    @Test
    void textWithoutImagePrefixUsesNormalChat() {
        when(agentCoordinator.execute("user", "给我生成一张小花的图片", null))
                .thenReturn(AgentCoordinator.AgentResult.text("请使用生图格式"));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("给我生成一张小花的图片")), status());

        assertThat(textValue(reply)).isEqualTo("请使用生图格式");
    }

    @Test
    void schedulerCanSeparateImageGenerationFromOrderedText() {
        assertThat(replyService.isImageGenerationMessage(List.of(text("生图：一只小猫")))).isTrue();
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
        verify(mediaDownloader, never()).downloadImages(any());
    }

    @Test
    void uploadedWordAndNaturalLanguageInstructionReturnPdf() {
        AiFile source = new AiFile("source.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[]{1, 2});
        byte[] pdf = new byte[]{3, 4, 5};
        when(fileSessions.consume("user")).thenReturn(Optional.of(source));
        when(agentCoordinator.execute("user", "帮我把这个变成 PDF", source))
                .thenReturn(AgentCoordinator.AgentResult.file("output.pdf", pdf));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("帮我把这个变成 PDF")), status());

        assertThat(reply).isInstanceOf(ILinkReply.DocumentFile.class);
        ILinkReply.DocumentFile file = (ILinkReply.DocumentFile) reply;
        assertThat(file.fileName()).isEqualTo("output.pdf");
        assertThat(file.bytes()).containsExactly(pdf);
        verify(fileSessions).consume("user");
        verify(agentCoordinator).execute("user", "帮我把这个变成 PDF", source);
    }

    @Test
    void naturalLanguageCanGenerateWordWithoutUploadingAFile() {
        byte[] docx = new byte[]{6, 7, 8};
        when(agentCoordinator.execute("user", "帮我写一份 Word 周报", null))
                .thenReturn(AgentCoordinator.AgentResult.file("output.docx", docx));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("帮我写一份 Word 周报")), status());

        assertThat(reply).isInstanceOf(ILinkReply.DocumentFile.class);
        ILinkReply.DocumentFile file = (ILinkReply.DocumentFile) reply;
        assertThat(file.fileName()).isEqualTo("output.docx");
        assertThat(file.bytes()).containsExactly(docx);
        verify(agentCoordinator).execute("user", "帮我写一份 Word 周报", null);
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
                ProtocolValues.ITEM_TYPE_TEXT, null, null, true, null, null,
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
