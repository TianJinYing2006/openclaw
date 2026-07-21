package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.bot.audio.TextToSpeechService;
import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import com.example.ykdsummer.bot.document.DocumentEditService;
import com.example.ykdsummer.bot.document.DocumentAnalysisService;
import com.example.ykdsummer.bot.document.DocumentIntentRouter;
import com.example.ykdsummer.bot.document.DocumentGenerationService;
import com.example.ykdsummer.bot.document.DocumentTextExtractor;
import com.example.ykdsummer.bot.document.RecentDocumentContextService;
import com.example.ykdsummer.bot.document.DocumentSessionService;
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
    private AiImageGenerationService imageGenerationService;
    private VideoAnalysisService videoAnalysisService;
    private TextToSpeechService textToSpeechService;
    private TtsVoiceSelectionService voiceSelectionService;
    private DocumentSessionService documentSessions;
    private DocumentEditService documentEditService;
    private DocumentAnalysisService documentAnalysisService;
    private DocumentGenerationService documentGenerationService;
    private DocumentTextExtractor documentTextExtractor;
    private RecentDocumentContextService recentDocumentContexts;

    @BeforeEach
    void setUp() {
        aiChatService = mock(AiChatService.class);
        mediaDownloader = mock(ILinkMediaDownloader.class);
        fileDownloader = mock(ILinkFileDownloader.class);
        imageGenerationService = mock(AiImageGenerationService.class);
        videoAnalysisService = mock(VideoAnalysisService.class);
        textToSpeechService = mock(TextToSpeechService.class);
        voiceSelectionService = mock(TtsVoiceSelectionService.class);
        documentSessions = mock(DocumentSessionService.class);
        documentEditService = mock(DocumentEditService.class);
        documentAnalysisService = mock(DocumentAnalysisService.class);
        documentGenerationService = mock(DocumentGenerationService.class);
        documentTextExtractor = mock(DocumentTextExtractor.class);
        recentDocumentContexts = mock(RecentDocumentContextService.class);
        replyService = new ILinkReplyService(
                aiChatService, mediaDownloader, fileDownloader, imageGenerationService, videoAnalysisService,
                textToSpeechService, voiceSelectionService, documentSessions, documentEditService,
                documentAnalysisService, new DocumentIntentRouter(), documentGenerationService,
                documentTextExtractor, recentDocumentContexts);
        when(aiChatService.isEnabled()).thenReturn(true);
        when(aiChatService.model()).thenReturn("gpt-5.6");
        when(recentDocumentContexts.augmentIfRelevant(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
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
        verify(recentDocumentContexts).clear("user-a");
        verify(aiChatService, never()).answer(any(), any(), any());
        verify(mediaDownloader, never()).downloadImages(any());
    }

    @Test
    void joinsAllTextItemsAndSendsTheCompleteQuestion() {
        when(aiChatService.answer(eq("user"), eq("第一段\n第二段"), eq(List.of())))
                .thenReturn("模型回答");

        ILinkReply reply = replyService.createReply(
                message("user"),
                List.of(text("第一段"), text("第二段")),
                status()
        );

        assertThat(textValue(reply)).isEqualTo("模型回答");
        verify(aiChatService).answer("user", "第一段\n第二段", List.of());
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
        verify(imageGenerationService, never()).generate(any(), any());
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
        when(imageGenerationService.generate("user", "一张小花的图片"))
                .thenReturn(AiImageGenerationService.Result.image(bytes));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("生图：一张小花的图片")), status());

        assertThat(reply).isInstanceOf(ILinkReply.Image.class);
        assertThat(((ILinkReply.Image) reply).bytes()).containsExactly(bytes);
        verify(aiChatService, never()).answer(any(), any(), any());
    }

    @Test
    void textWithoutImagePrefixAlwaysUsesNormalChat() {
        when(aiChatService.answer("user", "给我生成一张小花的图片", List.of()))
                .thenReturn("请使用生图格式");

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("给我生成一张小花的图片")), status());

        assertThat(textValue(reply)).isEqualTo("请使用生图格式");
        verify(imageGenerationService, never()).generate(any(), any());
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
    void fileOnlyEntersDocumentModeWithoutCallingTheChatModel() {
        AiFile file = new AiFile("report.pdf", "application/pdf", new byte[]{1, 2, 3});
        MessageItem fileItem = fileItem();
        when(fileDownloader.downloadFiles(any())).thenReturn(List.of(file));
        when(documentSessions.open("user", file)).thenReturn(new DocumentSessionService.DocumentSnapshot(
                "doc", "report.pdf", "pdf", 1, 1, Instant.now()));

        ILinkReply reply = replyService.createReply(message("user"), List.of(fileItem), status());

        assertThat(textValue(reply)).contains("已接收：report.pdf", "文档模式", "撤销", "复杂排版");
        verify(documentSessions).open("user", file);
        verify(aiChatService, never()).answer(any(), any(), any(), any());
        verify(mediaDownloader, never()).downloadImages(any());
    }

    @Test
    void plainTextInDocumentModeEditsAndReturnsFileWithCommands() {
        byte[] bytes = "changed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(documentSessions.hasActive("user")).thenReturn(true);
        when(documentEditService.edit("user", "把标题改掉")).thenReturn(new DocumentEditService.EditResult(
                "report_v2.txt", bytes,
                new DocumentSessionService.DocumentSnapshot(
                        "doc", "report.txt", "txt", 2, 2, Instant.now()),
                ""
        ));

        ILinkReply reply = replyService.createReply(message("user"), List.of(text("把标题改掉")), status());

        assertThat(reply).isInstanceOf(ILinkReply.DocumentFile.class);
        ILinkReply.DocumentFile fileReply = (ILinkReply.DocumentFile) reply;
        assertThat(fileReply.fileName()).isEqualTo("report_v2.txt");
        assertThat(fileReply.bytes()).containsExactly(bytes);
        assertThat(fileReply.followUpText()).contains("版本 v2", "修改：要求", "分析：问题", "完成", "关闭文件");
        verify(documentEditService).edit("user", "把标题改掉");
        verify(aiChatService, never()).answer(any(), any(), any());
    }

    @Test
    void problemStatementAnalyzesWithoutCreatingAVersion() {
        when(documentSessions.hasActive("user")).thenReturn(true);
        when(documentAnalysisService.analyze("user", "我认为第二自然段有问题")).thenReturn("第二段论据不足");

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("我认为第二自然段有问题")), status());

        assertThat(textValue(reply)).contains("第二段论据不足", "只做了分析", "应用建议");
        verify(documentAnalysisService).analyze("user", "我认为第二自然段有问题");
        verify(documentEditService, never()).edit(any(), any());
        verify(documentSessions, never()).addVersion(any(), any(), any());
    }

    @Test
    void ambiguousDocumentTextWaitsForExplicitConfirmation() {
        when(documentSessions.hasActive("user")).thenReturn(true);

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("第二自然段")), status());

        assertThat(textValue(reply)).contains("分析当前文件", "回复“分析”“修改”或“生成”", "不会改动文件");
        verify(documentSessions).savePendingInstruction("user", "第二自然段");
        verify(documentEditService, never()).edit(any(), any());
        verify(documentAnalysisService, never()).analyze(any(), any());
    }

    @Test
    void confirmationConsumesPendingInstructionAndApplySuggestionEdits() {
        byte[] bytes = "changed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(documentSessions.hasActive("user")).thenReturn(true);
        when(documentSessions.consumePendingInstruction("user")).thenReturn(Optional.of("第二自然段"));
        when(documentAnalysisService.analyze("user", "第二自然段")).thenReturn("建议压缩第二段");

        assertThat(textValue(replyService.createReply(message("user"), List.of(text("分析")), status())))
                .contains("建议压缩第二段", "没有修改文件");

        when(documentSessions.lastAnalysis("user")).thenReturn(Optional.of("建议压缩第二段"));
        when(documentEditService.edit(eq("user"), any())).thenReturn(new DocumentEditService.EditResult(
                "report_v2.txt", bytes,
                new DocumentSessionService.DocumentSnapshot("doc", "report.txt", "txt", 2, 2, Instant.now()), ""));
        ILinkReply applied = replyService.createReply(message("user"), List.of(text("应用建议")), status());
        assertThat(applied).isInstanceOf(ILinkReply.DocumentFile.class);
        verify(documentEditService).edit(eq("user"), org.mockito.ArgumentMatchers.contains("建议压缩第二段"));
    }

    @Test
    void generationCreatesIndependentFileWithoutChangingCurrentVersion() {
        byte[] bytes = "new-docx".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(documentSessions.hasActive("user")).thenReturn(true);
        DocumentSessionService.DocumentSnapshot source = new DocumentSessionService.DocumentSnapshot(
                "doc", "source.docx", "docx", 1, 1, Instant.now());
        when(documentGenerationService.generate("user", "基于它生成一份建议文案 Word"))
                .thenReturn(new DocumentGenerationService.GenerationResult(
                        "generated_document.docx", bytes, "docx", "完整建议文案", source, ""));

        ILinkReply reply = replyService.createReply(message("user"),
                List.of(text("生成：基于它生成一份建议文案 Word")), status());

        assertThat(reply).isInstanceOf(ILinkReply.DocumentFile.class);
        ILinkReply.DocumentFile file = (ILinkReply.DocumentFile) reply;
        assertThat(file.fileName()).isEqualTo("generated_document.docx");
        assertThat(file.followUpText()).contains("独立", "当前版本 v1 没有修改");
        verify(documentEditService, never()).edit(any(), any());
        verify(documentSessions, never()).addVersion(any(), any(), any());
        verify(recentDocumentContexts).remember("user", "generated_document.docx", "完整建议文案",
                RecentDocumentContextService.Kind.GENERATED);
    }

    @Test
    void recentDocumentContextIsAddedToNormalChatAfterDocumentModeEnds() {
        when(documentSessions.hasActive("user")).thenReturn(false);
        when(recentDocumentContexts.augmentIfRelevant("user", "刚才生成的文档讲了什么"))
                .thenReturn("带最近文档上下文的问题");
        when(aiChatService.answer("user", "带最近文档上下文的问题", List.of()))
                .thenReturn("这是刚才文档的内容");

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("刚才生成的文档讲了什么")), status());

        assertThat(textValue(reply)).isEqualTo("这是刚才文档的内容");
        verify(aiChatService).answer("user", "带最近文档上下文的问题", List.of());
    }

    @Test
    void explicitFormatGeneratesAFileFromRecentContextAfterDocumentModeEnds() {
        byte[] bytes = "new-pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String instruction = "把刚才的内容生成 PDF：整理一份改进建议";
        RecentDocumentContextService.RecentDocument reference =
                new RecentDocumentContextService.RecentDocument(
                        "report.docx", "最近文档正文", RecentDocumentContextService.Kind.MODIFIED);
        when(documentSessions.hasActive("user")).thenReturn(false);
        when(recentDocumentContexts.referenceForGeneration("user", instruction))
                .thenReturn(Optional.of(reference));
        when(documentGenerationService.generateFromRecent(reference, instruction))
                .thenReturn(new DocumentGenerationService.GenerationResult(
                        "generated_document.pdf", bytes, "pdf", "新建议正文", null, ""));

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text(instruction)), status());

        assertThat(reply).isInstanceOf(ILinkReply.DocumentFile.class);
        ILinkReply.DocumentFile file = (ILinkReply.DocumentFile) reply;
        assertThat(file.fileName()).isEqualTo("generated_document.pdf");
        assertThat(file.followUpText()).contains("最近文档上下文", "普通对话模式");
        verify(recentDocumentContexts).remember(
                "user", "generated_document.pdf", "新建议正文",
                RecentDocumentContextService.Kind.GENERATED);
        verify(aiChatService, never()).answer(any(), any(), any());
    }

    @Test
    void explicitFileGenerationWithoutRecentContextAsksForUpload() {
        String instruction = "请帮我生成一个 Word：整理成正式报告";
        when(documentSessions.hasActive("user")).thenReturn(false);
        when(recentDocumentContexts.referenceForGeneration("user", instruction))
                .thenReturn(Optional.empty());

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text(instruction)), status());

        assertThat(textValue(reply)).contains("没有找到最近文档", "先发送一个文件");
        verify(documentGenerationService, never()).generateFromRecent(any(), any());
        verify(aiChatService, never()).answer(any(), any(), any());
    }

    @Test
    void generationWithoutAFileFormatRemainsNormalChat() {
        when(documentSessions.hasActive("user")).thenReturn(false);
        when(aiChatService.answer("user", "生成一段产品建议文案", List.of()))
                .thenReturn("普通文本建议");

        ILinkReply reply = replyService.createReply(
                message("user"), List.of(text("生成一段产品建议文案")), status());

        assertThat(textValue(reply)).isEqualTo("普通文本建议");
        verify(documentGenerationService, never()).generateFromRecent(any(), any());
    }

    @Test
    void documentCommandsUndoUseOriginalFinishAndClose() {
        byte[] bytes = "version".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(documentSessions.hasActive("user")).thenReturn(true);
        when(documentSessions.currentFile("user")).thenReturn(
                new DocumentSessionService.VersionFile("report_v1.txt", "text/plain", bytes, 1));
        when(documentSessions.undo("user")).thenReturn(Optional.of(new DocumentSessionService.DocumentSnapshot(
                "doc", "report.txt", "txt", 1, 2, Instant.now())));
        when(documentSessions.useOriginal("user")).thenReturn(new DocumentSessionService.DocumentSnapshot(
                "doc", "report.txt", "txt", 1, 2, Instant.now()));

        assertThat(((ILinkReply.DocumentFile) replyService.createReply(
                message("user"), List.of(text("撤销")), status())).followUpText()).contains("已撤销到版本 v1");
        assertThat(((ILinkReply.DocumentFile) replyService.createReply(
                message("user"), List.of(text("使用原版")), status())).followUpText()).contains("已切换到原版");
        assertThat(((ILinkReply.DocumentFile) replyService.createReply(
                message("user"), List.of(text("完成")), status())).followUpText()).contains("退出文档模式");
        verify(documentSessions).close("user");

        assertThat(textValue(replyService.createReply(
                message("user"), List.of(text("关闭文件")), status()))).contains("已退出文档模式");
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
