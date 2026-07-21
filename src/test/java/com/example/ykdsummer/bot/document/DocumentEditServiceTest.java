package com.example.ykdsummer.bot.document;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.service.LlmGateway;
import com.example.ykdsummer.bot.config.DocumentEditProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentEditServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void resendsCurrentFileWithInstructionAndStoresRenderedVersion() {
        DocumentEditProperties properties = new DocumentEditProperties();
        properties.setStorageDirectory(tempDir);
        DocumentSessionService sessions = new DocumentSessionService(properties);
        sessions.open("user", new AiFile("notes.md", "text/markdown", bytes("旧内容")));
        RecordingGateway gateway = new RecordingGateway();
        DocumentEditService service = new DocumentEditService(sessions, gateway, new DocumentRenderer());

        DocumentEditService.EditResult result = service.edit("user", "把标题改成新标题");

        assertThat(gateway.prompt).contains("把标题改成新标题", "附件是当前版本");
        assertThat(gateway.reasoningEffort).isEqualTo("low");
        assertThat(gateway.file.fileName()).contains("original.md");
        assertThat(new String(gateway.file.bytes(), StandardCharsets.UTF_8)).isEqualTo("旧内容");
        assertThat(result.snapshot().currentVersion()).isEqualTo(2);
        assertThat(new String(result.bytes(), StandardCharsets.UTF_8)).isEqualTo("# 新标题\n新内容");
    }

    @Test
    void legacyDocCanBeAnalyzedButEditRequiresDocxConversion() {
        DocumentEditProperties properties = new DocumentEditProperties();
        properties.setStorageDirectory(tempDir);
        DocumentSessionService sessions = new DocumentSessionService(properties);
        sessions.open("user", new AiFile("legacy.doc", "application/msword", bytes("legacy")));
        RecordingGateway gateway = new RecordingGateway();
        DocumentEditService service = new DocumentEditService(sessions, gateway, new DocumentRenderer());

        assertThatThrownBy(() -> service.edit("user", "修改标题"))
                .isInstanceOf(DocumentEditException.class)
                .extracting(exception -> ((DocumentEditException) exception).userMessage())
                .asString().contains("转换为 .docx");
        assertThat(sessions.current("user")).get().extracting(DocumentSessionService.DocumentSnapshot::currentVersion)
                .isEqualTo(1);
    }

    @Test
    void officePromptRequestsMinimalJsonOperations() {
        assertThat(DocumentEditService.buildPrompt("xlsx", "把 A2 改成 100"))
                .contains("最少操作", "set_cell", "真实工作表名", "operations")
                .doesNotContain("输出完整 CSV");
    }

    @Test
    void semanticDocxRequestUsesLocalTextAndRebuildsTheWholeDocument() throws Exception {
        DocumentEditProperties properties = new DocumentEditProperties();
        properties.setStorageDirectory(tempDir.resolve("rewrite"));
        DocumentSessionService sessions = new DocumentSessionService(properties);
        DocumentRenderer renderer = new DocumentRenderer();
        sessions.open("user", new AiFile("销售工作计划.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                renderer.render("docx", "# 原销售计划\n旧目标\n旧措施")));
        RewriteGateway gateway = new RewriteGateway();
        DocumentEditService service = new DocumentEditService(sessions, gateway, renderer);

        DocumentEditService.EditResult result = service.edit("user", "生成一个你的理解word给我");

        assertThat(gateway.prompt).contains("原销售计划", "replace_document", "不能只在原文末尾追加一段总结");
        assertThat(gateway.files).isEmpty();
        assertThat(result.warning()).contains("全文重构", "原版及旧版本仍可撤销恢复");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(result.bytes()))) {
            assertThat(document.getParagraphs()).extracting(paragraph -> paragraph.getText())
                    .contains("新销售工作计划", "提升客户转化率")
                    .doesNotContain("原销售计划", "旧目标", "旧措施");
        }
        assertThat(result.snapshot().currentVersion()).isEqualTo(2);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingGateway implements LlmGateway {
        private String prompt;
        private AiFile file;
        private String reasoningEffort;

        @Override
        public ModelReply generate(List<ConversationMessage> history, String prompt,
                                   List<AiImage> images, List<AiFile> files, String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return generate(history, prompt, images, files);
        }

        @Override
        public ModelReply generate(List<ConversationMessage> history, String prompt,
                                   List<AiImage> images, List<AiFile> files) {
            this.prompt = prompt;
            this.file = files.getFirst();
            return new ModelReply(
                    "<<<DOCUMENT_CONTENT_BEGIN>>>\n# 新标题\n新内容\n<<<DOCUMENT_CONTENT_END>>>",
                    "test-model");
        }
    }

    private static final class RewriteGateway implements LlmGateway {
        private String prompt;
        private List<AiFile> files;

        @Override
        public ModelReply generate(List<ConversationMessage> history, String prompt,
                                   List<AiImage> images, List<AiFile> files) {
            this.prompt = prompt;
            this.files = List.copyOf(files);
            return new ModelReply("<<<DOCUMENT_CONTENT_BEGIN>>>\n"
                    + "{\"operations\":[{\"type\":\"replace_document\","
                    + "\"content\":\"# 新销售工作计划\\n## 目标\\n提升客户转化率\"}]}\n"
                    + "<<<DOCUMENT_CONTENT_END>>>", "test-model");
        }
    }
}
