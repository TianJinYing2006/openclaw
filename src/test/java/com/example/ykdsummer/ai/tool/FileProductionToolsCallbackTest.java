package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class FileProductionToolsCallbackTest {

    @Test
    void producesNamedCsvAndPptxAttachmentsAsIndependentAssets() throws Exception {
        LocalDocumentAssetStore store = new LocalDocumentAssetStore(Files.createTempDirectory("file-production-assets"));
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("file-user");
        FileProductionTools tools = new FileProductionTools(store, artifacts);

        String csv = callback(tools, "produce_file").call("""
                {"fileName":"七月数据.csv","format":"csv","content":"姓名,金额\\n小王,=1+1","summary":"七月数据清单"}
                """);
        String pptx = callback(tools, "produce_file").call("""
                {"fileName":"项目简报.pptx","format":"pptx","content":"项目简报\\n本周完成需求梳理\\n---\\n下周计划\\n联调与验收"}
                """);

        assertThat(csv).contains("七月数据.csv", "文档编号", "格式 csv");
        assertThat(pptx).contains("项目简报.pptx", "文档编号", "格式 pptx");
        assertThat(store.recent("file-user", 8))
                .extracting(LocalDocumentAssetStore.StoredDocument::format)
                .containsExactlyInAnyOrder("csv", "pptx");
        assertThat(artifacts.finish()).satisfiesExactly(
                artifact -> {
                    assertThat(artifact.type()).isEqualTo(AiArtifact.Type.DOCUMENT);
                    assertThat(artifact.fileName()).isEqualTo("七月数据.csv");
                    assertThat(new String(artifact.bytes(), StandardCharsets.UTF_8)).contains("'=1+1");
                },
                artifact -> {
                    assertThat(artifact.type()).isEqualTo(AiArtifact.Type.DOCUMENT);
                    assertThat(artifact.fileName()).isEqualTo("项目简报.pptx");
                    assertThat(artifact.bytes()).startsWith((byte) 'P', (byte) 'K');
                }
        );
    }

    @Test
    void rejectsAConflictingFileNameExtensionWithoutEmittingAnAttachment() throws Exception {
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("file-user");
        FileProductionTools tools = new FileProductionTools(
                new LocalDocumentAssetStore(Files.createTempDirectory("file-production-invalid")), artifacts
        );

        String result = callback(tools, "produce_file")
                .call("{\"fileName\":\"日报.json\",\"format\":\"csv\",\"content\":\"名称,状态\\n接口,完成\"}");

        assertThat(result).contains("文件生成失败", "文件名扩展名与输出格式不一致");
        assertThat(artifacts.finish()).isEmpty();
    }

    private static ToolCallback callback(Object tools, String name) {
        return Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
