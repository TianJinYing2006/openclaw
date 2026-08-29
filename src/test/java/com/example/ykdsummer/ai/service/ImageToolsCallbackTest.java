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

/** 验证图片 Tool 能创建可查询的任务和图片资产，而不是在本轮直接交付附件。 */
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

        assertThat(generated).contains("图片任务已提交后台", "任务编号", "执行中");
        assertThat(revised).contains("图片修改任务已提交后台", "任务编号", "执行中");
        assertThat(store.current("image-user").orElseThrow().version()).isEqualTo(2);
        assertThat(taskStore.recent("image-user", 8))
                .extracting(ImageTaskStatusStore.ImageTask::status)
                .containsExactly(ImageTaskStatusStore.Status.SUCCEEDED, ImageTaskStatusStore.Status.SUCCEEDED);
        assertThat(artifacts.finish()).isEmpty();
        verify(imageService).generate(eq("image-user"), anyString());
        verify(imageService).revise(eq("image-user"), anyString(), anyString());
    }

    @Test
    void generatedTaskFailsWhenItsResultCannotBePersisted() {
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
        ImageTaskStatusStore taskStore = new ImageTaskStatusStore();
        ImageTools tools = new ImageTools(imageService, store, artifacts, ImageInspectionService.unavailable(),
                taskStore, AiTraceLogger.disabled());

        String result = callback(tools, "generate_image")
                .call("{\"prompt\":\"一只在草地上的小狗\"}");
        String taskId = taskStore.latest("image-user").orElseThrow().taskId();

        assertThat(result).contains("图片任务已提交后台", "任务编号", "执行中");
        assertThat(taskStore.find("image-user", taskId)).get()
                .extracting(ImageTaskStatusStore.ImageTask::status, ImageTaskStatusStore.ImageTask::failureSummary)
                .containsExactly(ImageTaskStatusStore.Status.FAILED, "图片生成服务请求失败");
        assertThat(artifacts.finish()).isEmpty();
    }

    @Test
    void revisedTaskFailsWhenNewVersionCannotBeSaved() {
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
        ImageTaskStatusStore taskStore = new ImageTaskStatusStore();
        ImageTools tools = new ImageTools(imageService, store, artifacts, ImageInspectionService.unavailable(),
                taskStore, AiTraceLogger.disabled());

        String result = callback(tools, "create_image_revision")
                .call("{\"assetId\":\"" + assetId + "\",\"prompt\":\"把小狗的围巾改成蓝色\"}");
        String taskId = taskStore.latest("image-user").orElseThrow().taskId();

        assertThat(result).contains("图片修改任务已提交后台", "任务编号", "执行中");
        assertThat(taskStore.find("image-user", taskId)).get()
                .extracting(ImageTaskStatusStore.ImageTask::status, ImageTaskStatusStore.ImageTask::failureSummary)
                .containsExactly(ImageTaskStatusStore.Status.FAILED, "图片修改服务请求失败");
        assertThat(artifacts.finish()).isEmpty();
    }

    @Test
    void inspectImageResolvesCurrentAliasWithoutAFailedLookup() {
        StoredImage current = new StoredImage("img_current_real", 2, Path.of("current.png"), "", "",
                Instant.EPOCH, "image/png", "uploaded", "");
        AiImageGenerationService imageService = mock(AiImageGenerationService.class);
        LocalImageAssetStore store = mock(LocalImageAssetStore.class);
        ImageInspectionService inspection = mock(ImageInspectionService.class);
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("image-user");
        when(store.current("image-user")).thenReturn(Optional.of(current));
        when(inspection.inspect(current, "这件上衣是什么颜色")).thenReturn("深灰色上衣");
        ImageTools tools = new ImageTools(imageService, store, artifacts, inspection, AiTraceLogger.disabled());

        String result = tools.inspectImage("img_current", "这件上衣是什么颜色");

        assertThat(result).contains("img_current_real", "深灰色上衣").doesNotContain("找不到图片资源");
        verify(store).annotate("image-user", "img_current_real", "深灰色上衣");
        artifacts.finish();
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
