package com.example.ykdsummer.bot.document;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.service.LlmGateway;
import com.example.ykdsummer.bot.config.DocumentEditProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentGenerationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesIndependentPdfFromCurrentDocumentWithoutAddingVersion() throws Exception {
        DocumentEditProperties properties = new DocumentEditProperties();
        properties.setStorageDirectory(tempDir);
        DocumentSessionService sessions = new DocumentSessionService(properties);
        sessions.open("user", new AiFile("source.md", "text/markdown",
                "# 产品介绍\n产品适合企业客户".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        RecordingGateway gateway = new RecordingGateway();
        DocumentGenerationService service = new DocumentGenerationService(
                sessions, gateway, new DocumentTextExtractor(), properties, new DocumentRenderer());

        DocumentGenerationService.GenerationResult result = service.generate(
                "user", "另外生成一份关于它的建议文案 PDF");

        assertThat(result.fileName()).isEqualTo("generated_document.pdf");
        assertThat(result.sourceSnapshot().currentVersion()).isEqualTo(1);
        assertThat(sessions.current("user")).get()
                .extracting(DocumentSessionService.DocumentSnapshot::currentVersion).isEqualTo(1);
        assertThat(gateway.files).isEmpty();
        assertThat(gateway.prompt).contains("产品适合企业客户", "另外生成", "不是修改、续写或覆盖原文件");
        try (var document = Loader.loadPDF(result.bytes())) {
            assertThat(new PDFTextStripper().getText(document)).contains("产品建议", "加强客户回访");
        }
    }

    @Test
    void selectsRequestedFormatOrFallsBackToSourceType() {
        assertThat(DocumentGenerationService.targetExtension("生成一个 PPT 演示文稿", "docx")).isEqualTo("pptx");
        assertThat(DocumentGenerationService.targetExtension("生成 Excel 电子表格", "docx")).isEqualTo("xlsx");
        assertThat(DocumentGenerationService.targetExtension("生成建议文案", "docx")).isEqualTo("docx");
        assertThat(DocumentGenerationService.targetExtension("生成 Markdown 建议文案", "docx")).isEqualTo("md");
        assertThat(DocumentGenerationService.targetExtension("另生成一份", "doc")).isEqualTo("docx");
    }

    @Test
    void generatesFromRecentTextWithoutReopeningDocumentSession() throws Exception {
        DocumentEditProperties properties = new DocumentEditProperties();
        properties.setStorageDirectory(tempDir.resolve("recent"));
        DocumentSessionService sessions = new DocumentSessionService(properties);
        RecordingGateway gateway = new RecordingGateway();
        DocumentGenerationService service = new DocumentGenerationService(
                sessions, gateway, new DocumentTextExtractor(), properties, new DocumentRenderer());
        RecentDocumentContextService.RecentDocument reference =
                new RecentDocumentContextService.RecentDocument(
                        "刚才的方案.docx", "企业协作平台需要加强客户回访",
                        RecentDocumentContextService.Kind.MODIFIED);

        DocumentGenerationService.GenerationResult result = service.generateFromRecent(
                reference, "生成 PDF：整理成一份改进建议");

        assertThat(result.fileName()).isEqualTo("generated_document.pdf");
        assertThat(result.sourceSnapshot()).isNull();
        assertThat(sessions.current("user")).isEmpty();
        assertThat(gateway.files).isEmpty();
        assertThat(gateway.prompt).contains("企业协作平台需要加强客户回访", "生成 PDF");
        try (var document = Loader.loadPDF(result.bytes())) {
            assertThat(new PDFTextStripper().getText(document)).contains("产品建议", "加强客户回访");
        }
    }

    private static final class RecordingGateway implements LlmGateway {
        private String prompt;
        private List<AiFile> files;

        @Override
        public ModelReply generate(List<ConversationMessage> history, String prompt,
                                   List<AiImage> images, List<AiFile> files) {
            this.prompt = prompt;
            this.files = List.copyOf(files);
            return new ModelReply("<<<GENERATED_DOCUMENT_BEGIN>>>\n"
                    + "# 产品建议\n加强客户回访\n<<<GENERATED_DOCUMENT_END>>>", "test-model");
        }
    }
}
