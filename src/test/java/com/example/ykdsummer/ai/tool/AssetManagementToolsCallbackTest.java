package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class AssetManagementToolsCallbackTest {

    @Test
    void listsSelectsDescribesAndResendsImageAndDocumentAssets() throws Exception {
        LocalImageAssetStore imageStore = new LocalImageAssetStore(Files.createTempDirectory("asset-images"));
        LocalDocumentAssetStore documentStore = new LocalDocumentAssetStore(Files.createTempDirectory("asset-documents"));
        byte[] imageBytes = {1, 2, 3, 4};
        byte[] documentBytes = "日报内容".getBytes(StandardCharsets.UTF_8);
        var image = imageStore.saveGenerated("asset-user", "一只戴蓝色围巾的小狗", imageBytes, null);
        var document = documentStore.create("asset-user", "今日进展", "txt", documentBytes, "日报初稿");
        imageStore.clearCurrent("asset-user");
        documentStore.clearCurrent("asset-user");
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("asset-user");
        AssetManagementTools tools = new AssetManagementTools(imageStore, documentStore, artifacts);

        String listed = callback(tools, "list_recent_assets").call("{\"type\":\"all\"}");
        String selectedImage = callback(tools, "select_asset")
                .call("{\"assetId\":\"" + image.assetId() + "\"}");
        String described = callback(tools, "describe_asset").call("{}");
        String resentImage = callback(tools, "resend_asset")
                .call("{\"assetId\":\"" + image.assetId() + "\"}");
        String resentDocument = callback(tools, "resend_asset")
                .call("{\"assetId\":\"" + document.assetId() + "\",\"version\":1}");

        assertThat(listed).contains("最近图片", image.assetId(), "最近文档", document.assetId());
        assertThat(selectedImage).contains("已选择当前图片", image.assetId(), "v1");
        assertThat(described).contains("当前图片", image.assetId(), "当前没有文档");
        assertThat(resentImage).contains("图片会重新发送给用户", image.assetId());
        assertThat(resentDocument).contains("文档会重新发送给用户", document.assetId());
        assertThat(artifacts.finish()).satisfiesExactly(
                artifact -> {
                    assertThat(artifact.type()).isEqualTo(AiArtifact.Type.IMAGE);
                    assertThat(artifact.bytes()).containsExactly(imageBytes);
                    assertThat(artifact.assetId()).isEqualTo(image.assetId());
                },
                artifact -> {
                    assertThat(artifact.type()).isEqualTo(AiArtifact.Type.DOCUMENT);
                    assertThat(artifact.bytes()).containsExactly(documentBytes);
                    assertThat(artifact.assetId()).isEqualTo(document.assetId());
                    assertThat(artifact.fileName()).isEqualTo("今日进展.txt");
                }
        );
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
