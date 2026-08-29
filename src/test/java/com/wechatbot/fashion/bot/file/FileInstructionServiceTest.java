package com.wechatbot.fashion.bot.file;

import com.wechatbot.fashion.ai.model.AiFile;
import com.wechatbot.fashion.ai.model.AiArtifact;
import com.wechatbot.fashion.ai.service.AiChatService;
import com.wechatbot.fashion.bot.config.FileProcessingProperties;
import com.wechatbot.fashion.bot.document.DocumentRenderer;
import com.wechatbot.fashion.bot.document.DocumentTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileInstructionServiceTest {

    private AiChatService aiChatService;
    private DocumentTextExtractor textExtractor;
    private FileInstructionService service;

    @BeforeEach
    void setUp() throws Exception {
        aiChatService = mock(AiChatService.class);
        textExtractor = mock(DocumentTextExtractor.class);
        service = new FileInstructionService(aiChatService, textExtractor, new FileProcessingProperties(),
                new LocalDocumentAssetStore(Files.createTempDirectory("document-assets")));
    }

    @Test
    void ordinaryModelReplyRemainsText() {
        when(aiChatService.answerWithInternalPromptRich(eq("user"), eq("你好"), any(), eq(List.of())))
                .thenReturn(AiChatService.AssistantAnswer.text("你好，有什么可以帮你？"));

        FileInstructionService.Result result = service.process("user", "你好", null);

        assertThat(result.hasFile()).isFalse();
        assertThat(result.text()).isEqualTo("你好，有什么可以帮你？");
    }

    @Test
    void transientImageArtifactBecomesAnImageReplyResult() {
        byte[] png = {4, 3, 2, 1};
        when(aiChatService.answerWithInternalPromptRich(eq("user"), eq("生成一张小狗图片"), any(), eq(List.of())))
                .thenReturn(new AiChatService.AssistantAnswer("图片已经生成",
                        List.of(AiArtifact.transientImage(png, "仅本轮发送"))));

        FileInstructionService.Result result = service.process("user", "生成一张小狗图片", null);

        assertThat(result.hasImage()).isTrue();
        assertThat(result.imageBytes()).containsExactly(png);
        assertThat(result.text()).isEqualTo("图片已经生成");
    }

    @Test
    void preservesMultipleImageArtifactsForSequentialWechatDelivery() {
        when(aiChatService.answerWithInternalPromptRich(eq("user"), eq("展示衣橱"), any(), eq(List.of())))
                .thenReturn(new AiChatService.AssistantAnswer("已发两页衣橱图", List.of(
                        AiArtifact.image(new byte[]{1}, "第1页", "", 0),
                        AiArtifact.image(new byte[]{2}, "第2页", "", 0))));

        FileInstructionService.Result result = service.process("user", "展示衣橱", null);

        assertThat(result.hasImagePages()).isTrue();
        assertThat(result.imagePages()).containsExactly(new byte[]{1}, new byte[]{2});
    }

    @Test
    void uploadedWordContentAndInstructionCanProduceAReadablePdf() throws Exception {
        AiFile source = new AiFile("source.docx", "application/octet-stream", new byte[]{1, 2, 3});
        when(textExtractor.extract(eq("docx"), any())).thenReturn(Optional.of("原文标题\n原文正文"));
        byte[] pdf = new DocumentRenderer().render("pdf", "原文标题\n原文正文");
        when(aiChatService.answerWithInternalPromptRich(eq("user"), eq("帮我把这个变成 PDF"), any(), eq(List.of())))
                .thenReturn(new AiChatService.AssistantAnswer("已转换完成",
                        List.of(AiArtifact.document(pdf, "source.pdf", "文档 v2", "doc_test", 2))));

        FileInstructionService.Result result = service.process("user", "帮我把这个变成 PDF", source);

        assertThat(result.hasFile()).isTrue();
        assertThat(result.fileName()).isEqualTo("source.pdf");
        try (var document = Loader.loadPDF(result.bytes())) {
            assertThat(new PDFTextStripper().getText(document)).contains("原文标题", "原文正文");
        }
        ArgumentCaptor<String> modelPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).answerWithInternalPromptRich(
                eq("user"), eq("帮我把这个变成 PDF"), modelPrompt.capture(), eq(List.of()));
        assertThat(modelPrompt.getValue()).contains("create_document", "replace_document_content", "assetId=doc_", "原文标题", "帮我把这个变成 PDF")
                .doesNotContain("FILE_GEN||");
    }

    @Test
    void uploadedPdfWithExtractableTextUsesToolCapableTextRoute() throws Exception {
        LocalDocumentAssetStore store = new LocalDocumentAssetStore(Files.createTempDirectory("pdf-assets"));
        FileInstructionService pdfService = new FileInstructionService(
                aiChatService, new DocumentTextExtractor(), new FileProcessingProperties(), store);
        byte[] pdf = new DocumentRenderer().render("pdf", "PDF 唯一标记：本地提取后应走工具链");
        AiFile source = new AiFile("source.pdf", "application/pdf", pdf);
        when(aiChatService.answerWithInternalPromptRich(eq("user"), eq("总结这份 PDF"), any(), eq(List.of())))
                .thenReturn(AiChatService.AssistantAnswer.text("已读取 PDF"));

        FileInstructionService.Result result = pdfService.process("user", "总结这份 PDF", source);

        assertThat(result.text()).isEqualTo("已读取 PDF");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).answerWithInternalPromptRich(
                eq("user"), eq("总结这份 PDF"), prompt.capture(), eq(List.of()));
        assertThat(prompt.getValue()).contains("assetId=doc_", "PDF 唯一标记：本地提取后应走工具链");
    }

    @Test
    void naturalLanguageCanProduceAReadableWordWithoutAnUpload() throws Exception {
        byte[] docx = new DocumentRenderer().render("docx", "# 本周工作\n完成文件生成功能");
        when(aiChatService.answerWithInternalPromptRich(eq("user"), eq("写一份 Word 周报"), any(), eq(List.of())))
                .thenReturn(new AiChatService.AssistantAnswer("周报已生成",
                        List.of(AiArtifact.document(docx, "周报.docx", "文档 v1", "doc_week", 1))));

        FileInstructionService.Result result = service.process("user", "写一份 Word 周报", null);

        assertThat(result.fileName()).isEqualTo("周报.docx");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(result.bytes()))) {
            assertThat(document.getParagraphs()).extracting(paragraph -> paragraph.getText())
                    .contains("本周工作", "完成文件生成功能");
        }
    }

    @Test
    void oldInternalMarkerIsNowReturnedAsPlainTextInsteadOfBeingExecuted() {
        when(aiChatService.answerWithInternalPromptRich(eq("user"), eq("测试"), any(), eq(List.of())))
                .thenReturn(AiChatService.AssistantAnswer.text("FILE_GEN||not-json"));

        FileInstructionService.Result result = service.process("user", "测试", null);

        assertThat(result.hasFile()).isFalse();
        assertThat(result.text()).isEqualTo("FILE_GEN||not-json");
    }

    @Test
    void toolGeneratedAudioIsReturnedWithoutPretendingItIsARegularFile() {
        byte[] mp3 = {1, 2, 3};
        when(aiChatService.answerWithInternalPromptRich(eq("user"), eq("请用语音回答"), any(), eq(List.of())))
                .thenReturn(new AiChatService.AssistantAnswer("这是文字答案",
                        List.of(AiArtifact.audio(mp3, "answer.mp3", "语音"))));

        FileInstructionService.Result result = service.process("user", "请用语音回答", null);

        assertThat(result.hasAudio()).isTrue();
        assertThat(result.hasFile()).isFalse();
        assertThat(result.text()).isEqualTo("这是文字答案");
        assertThat(result.audioFileName()).isEqualTo("answer.mp3");
        assertThat(result.audioBytes()).containsExactly(mp3);
    }
}
