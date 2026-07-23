package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.ai.tool.ImageTools;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/** 验证模型返回图片 tool_call 后，Spring AI 能绑定参数并真正生成可回传的图片资源。 */
class ImageToolsCallbackTest {

    @Test
    void modelToolCallsCreateAndReviseTrackedImage() throws Exception {
        AiImageGenerationService imageService = mock(AiImageGenerationService.class);
        when(imageService.generate(eq("image-user"), anyString())).thenReturn(
                AiImageGenerationService.Result.image(new byte[]{1, 2, 3})
        );
        when(imageService.revise(eq("image-user"), anyString(), anyString())).thenReturn(
                AiImageGenerationService.Result.image(new byte[]{4, 5, 6})
        );
        LocalImageAssetStore store = new LocalImageAssetStore(Files.createTempDirectory("image-tool-assets"));
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("image-user");
        ImageTaskStatusStore taskStore = new ImageTaskStatusStore();
        ImageTools tools = new ImageTools(imageService, store, artifacts, ImageInspectionService.unavailable(),
                taskStore, AiTraceLogger.disabled());

        String generated = callback(tools, "generate_image")
                .call("{\"prompt\":\"一只戴围巾的橘猫，水彩风格\"}");
        String assetId = store.current("image-user").orElseThrow().assetId();
        String revised = callback(tools, "create_image_revision")
                .call("{\"assetId\":\"" + assetId + "\",\"prompt\":\"保留橘猫，围巾改成蓝色\"}");

        assertThat(generated).contains("图片编号", assetId, "v1", "任务编号", "已成功");
        assertThat(revised).contains(assetId, "v2", "任务编号", "已成功");
        assertThat(store.current("image-user").orElseThrow().version()).isEqualTo(2);
        assertThat(taskStore.recent("image-user", 8))
                .extracting(ImageTaskStatusStore.ImageTask::status)
                .containsExactly(ImageTaskStatusStore.Status.SUCCEEDED, ImageTaskStatusStore.Status.SUCCEEDED);
        assertThat(artifacts.finish()).extracting(AiArtifact::type)
                .containsExactly(AiArtifact.Type.IMAGE, AiArtifact.Type.IMAGE);
        verify(imageService).generate(eq("image-user"), anyString());
        verify(imageService).revise(eq("image-user"), anyString(), anyString());
    }

    @Test
    void generatedBytesRemainDeliverableWhenOssPersistenceFails() {
        byte[] png = {7, 8, 9};
        AiImageGenerationService imageService = mock(AiImageGenerationService.class);
        when(imageService.generate(eq("image-user"), anyString())).thenReturn(
                AiImageGenerationService.Result.image(png)
        );
        LocalImageAssetStore store = mock(LocalImageAssetStore.class);
        when(store.saveGenerated(eq("image-user"), anyString(), any(byte[].class), nullable(String.class)))
                .thenThrow(new IllegalStateException("OSS unavailable"));
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("image-user");
        ImageTools tools = new ImageTools(imageService, store, artifacts);

        String result = callback(tools, "generate_image")
                .call("{\"prompt\":\"一只在草地上的小狗\"}");
        java.util.List<AiArtifact> generated = artifacts.finish();

        assertThat(result).contains("图片已生成", "图片资产保存失败", "本轮仍可查看");
        assertThat(generated).singleElement().satisfies(artifact -> {
            assertThat(artifact.type()).isEqualTo(AiArtifact.Type.IMAGE);
            assertThat(artifact.bytes()).containsExactly(png);
            assertThat(artifact.assetId()).isNull();
            assertThat(artifact.version()).isZero();
        });
    }

    @Test
    void revisedBytesRemainDeliverableWhenNewVersionCannotBeSaved() {
        byte[] revisedPng = {6, 5, 4};
        String assetId = "img_abc123def456";
        StoredImage base = new StoredImage(assetId, 1, Path.of("base.png"), "一只小狗", "",
                Instant.EPOCH, "image/png", "generated", "");
        AiImageGenerationService imageService = mock(AiImageGenerationService.class);
        when(imageService.revise(eq("image-user"), anyString(), eq("https://example.test/base.png")))
                .thenReturn(AiImageGenerationService.Result.image(revisedPng));
        LocalImageAssetStore store = mock(LocalImageAssetStore.class);
        when(store.latest("image-user", assetId)).thenReturn(Optional.of(base));
        when(store.signedReadUrl(base)).thenReturn("https://example.test/base.png");
        when(store.saveRevision(eq("image-user"), eq(assetId), anyString(), any(byte[].class), nullable(String.class)))
                .thenThrow(new IllegalStateException("OSS unavailable"));
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("image-user");
        ImageTools tools = new ImageTools(imageService, store, artifacts);

        String result = callback(tools, "create_image_revision")
                .call("{\"assetId\":\"" + assetId + "\",\"prompt\":\"把小狗的围巾改成蓝色\"}");
        java.util.List<AiArtifact> generated = artifacts.finish();

        assertThat(result).contains("图片修改结果已生成", "未能保存为新版本", "当前图片资产保持不变");
        assertThat(generated).singleElement().satisfies(artifact -> {
            assertThat(artifact.type()).isEqualTo(AiArtifact.Type.IMAGE);
            assertThat(artifact.bytes()).containsExactly(revisedPng);
            assertThat(artifact.assetId()).isNull();
            assertThat(artifact.version()).isZero();
        });
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
