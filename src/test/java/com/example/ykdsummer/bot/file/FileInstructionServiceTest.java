package com.example.ykdsummer.bot.file;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.bot.config.FileProcessingProperties;
import com.example.ykdsummer.bot.document.DocumentRenderer;
import com.example.ykdsummer.bot.document.DocumentTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
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
    void setUp() {
        aiChatService = mock(AiChatService.class);
        textExtractor = mock(DocumentTextExtractor.class);
        service = new FileInstructionService(
                aiChatService, textExtractor, new FileProcessingProperties(), new DocumentRenderer());
    }

    @Test
    void ordinaryModelReplyRemainsText() {
        when(aiChatService.answerWithInternalPrompt(eq("user"), eq("你好"), any(), eq(List.of())))
                .thenReturn("你好，有什么可以帮你？");

        FileInstructionService.Result result = service.process("user", "你好", null);

        assertThat(result.hasFile()).isFalse();
        assertThat(result.text()).isEqualTo("你好，有什么可以帮你？");
    }

    @Test
    void uploadedWordContentAndInstructionCanProduceAReadablePdf() throws Exception {
        AiFile source = new AiFile("source.docx", "application/octet-stream", new byte[]{1, 2, 3});
        when(textExtractor.extract("docx", source.bytes())).thenReturn(Optional.of("原文标题\n原文正文"));
        when(aiChatService.answerWithInternalPrompt(eq("user"), eq("帮我把这个变成 PDF"), any(), eq(List.of())))
                .thenReturn("FILE_GEN||{\"need_file\":true,\"format\":\"pdf\","
                        + "\"content\":\"原文标题\\n原文正文\"}");

        FileInstructionService.Result result = service.process("user", "帮我把这个变成 PDF", source);

        assertThat(result.hasFile()).isTrue();
        assertThat(result.fileName()).isEqualTo("output.pdf");
        try (var document = Loader.loadPDF(result.bytes())) {
            assertThat(new PDFTextStripper().getText(document)).contains("原文标题", "原文正文");
        }
        ArgumentCaptor<String> modelPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).answerWithInternalPrompt(
                eq("user"), eq("帮我把这个变成 PDF"), modelPrompt.capture(), eq(List.of()));
        assertThat(modelPrompt.getValue()).contains("FILE_GEN||", "source.docx", "原文标题", "帮我把这个变成 PDF");
    }

    @Test
    void naturalLanguageCanProduceAReadableWordWithoutAnUpload() throws Exception {
        when(aiChatService.answerWithInternalPrompt(eq("user"), eq("写一份 Word 周报"), any(), eq(List.of())))
                .thenReturn("FILE_GEN||{\"need_file\":true,\"format\":\"docx\","
                        + "\"content\":\"# 本周工作\\n完成文件生成功能\"}");

        FileInstructionService.Result result = service.process("user", "写一份 Word 周报", null);

        assertThat(result.fileName()).isEqualTo("output.docx");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(result.bytes()))) {
            assertThat(document.getParagraphs()).extracting(paragraph -> paragraph.getText())
                    .contains("本周工作", "完成文件生成功能");
        }
    }

    @Test
    void malformedOrUnsupportedInternalInstructionsFailSafely() {
        assertThat(service.parseResponse("FILE_GEN||not-json").text()).contains("解析失败");
        assertThat(service.parseResponse(
                "FILE_GEN||{\"need_file\":true,\"format\":\"exe\",\"content\":\"x\"}").text())
                .contains("只支持生成 Word、Excel、PDF 和 TXT");
    }
}
