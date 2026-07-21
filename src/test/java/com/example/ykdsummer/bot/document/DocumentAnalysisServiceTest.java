package com.example.ykdsummer.bot.document;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.service.LlmGateway;
import com.example.ykdsummer.bot.config.DocumentEditProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentAnalysisServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzesCurrentFileAndStoresSuggestionWithoutCreatingVersion() {
        DocumentEditProperties properties = new DocumentEditProperties();
        properties.setStorageDirectory(tempDir);
        DocumentSessionService sessions = new DocumentSessionService(properties);
        sessions.open("user", new AiFile("article.txt", "text/plain", "第一段\n第二段".getBytes()));
        RecordingGateway gateway = new RecordingGateway();
        DocumentAnalysisService service = new DocumentAnalysisService(sessions, gateway);

        String answer = service.analyze("user", "我认为第二自然段有问题");

        assertThat(answer).isEqualTo("第二段缺少论据，建议补充例子。");
        assertThat(gateway.prompt).contains("本地提取", "第一段", "第二段", "第二自然段有问题");
        assertThat(gateway.reasoningEffort).isEqualTo("low");
        assertThat(gateway.files).isEmpty();
        assertThat(sessions.current("user")).get()
                .extracting(DocumentSessionService.DocumentSnapshot::currentVersion).isEqualTo(1);
        assertThat(sessions.lastAnalysis("user")).contains(answer);
    }

    @Test
    void fallsBackToInputFileWhenLocalExtractionIsUnavailable() {
        DocumentEditProperties properties = new DocumentEditProperties();
        properties.setStorageDirectory(tempDir.resolve("legacy"));
        DocumentSessionService sessions = new DocumentSessionService(properties);
        sessions.open("user", new AiFile("legacy.doc", "application/msword", new byte[]{1, 2, 3}));
        RecordingGateway gateway = new RecordingGateway();

        new DocumentAnalysisService(sessions, gateway).analyze("user", "这是什么文件？");

        assertThat(gateway.files).singleElement().extracting(AiFile::fileName).asString().endsWith(".doc");
        assertThat(gateway.prompt).contains("分析附件");
    }

    private static final class RecordingGateway implements LlmGateway {
        private String prompt;
        private List<AiFile> files;
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
            this.files = List.copyOf(files);
            return new ModelReply("第二段缺少论据，建议补充例子。", "test-model");
        }
    }
}
