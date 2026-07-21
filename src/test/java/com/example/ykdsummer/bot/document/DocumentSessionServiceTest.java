package com.example.ykdsummer.bot.document;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.bot.config.DocumentEditProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSessionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void preservesOriginalCreatesVersionsAndUndoFollowsTheActualParent() throws Exception {
        DocumentSessionService service = service();
        service.open("user", new AiFile("报告.md", "text/markdown", bytes("original")));
        service.addVersion("user", bytes("version-2"), "第一次修改");
        service.useOriginal("user");
        service.addVersion("user", bytes("version-3-from-original"), "基于原版修改");

        assertThat(service.currentFile("user").version()).isEqualTo(3);
        assertThat(service.undo("user")).get().extracting(DocumentSessionService.DocumentSnapshot::currentVersion)
                .isEqualTo(1);
        assertThat(new String(service.currentFile("user").bytes(), StandardCharsets.UTF_8)).isEqualTo("original");

        var metadata = Files.walk(tempDir).filter(path -> path.getFileName().toString().equals("metadata.json"))
                .findFirst().orElseThrow();
        assertThat(Files.readString(metadata)).contains("\"parentVersion\" : 1", "第一次修改", "基于原版修改");
    }

    @Test
    void closeExitsModeWithoutDeletingFiles() throws Exception {
        DocumentSessionService service = service();
        service.open("user", new AiFile("notes.txt", "text/plain", bytes("original")));
        service.addVersion("user", bytes("changed"), "修改");

        assertThat(service.close("user")).isPresent();
        assertThat(service.hasActive("user")).isFalse();
        assertThat(Files.walk(tempDir).filter(Files::isRegularFile).toList()).hasSize(3);
    }

    @Test
    void storesPendingConfirmationAndLatestAnalysisWithoutChangingVersion() {
        DocumentSessionService service = service();
        service.open("user", new AiFile("notes.txt", "text/plain", bytes("original")));

        service.savePendingInstruction("user", "第二自然段");
        assertThat(service.consumePendingInstruction("user")).contains("第二自然段");
        assertThat(service.consumePendingInstruction("user")).isEmpty();

        service.saveLastAnalysis("user", "建议补充论据");
        assertThat(service.lastAnalysis("user")).contains("建议补充论据");
        assertThat(service.current("user")).get().extracting(DocumentSessionService.DocumentSnapshot::currentVersion)
                .isEqualTo(1);
    }

    private DocumentSessionService service() {
        DocumentEditProperties properties = new DocumentEditProperties();
        properties.setStorageDirectory(tempDir);
        return new DocumentSessionService(properties);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
