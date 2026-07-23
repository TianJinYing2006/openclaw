package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.service.ImageInspectionService;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Operation;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Status;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class ImageTaskStatusToolsCallbackTest {

    @Test
    void checksFailedTaskAndExplicitRetryCreatesANewDeliverableImageTask() throws Exception {
        ImageTaskStatusStore taskStore = new ImageTaskStatusStore();
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("task-user");
        AiImageGenerationService imageService = mock(AiImageGenerationService.class);
        when(imageService.generate(eq("task-user"), anyString()))
                .thenReturn(AiImageGenerationService.Result.image(new byte[]{1, 2, 3}));
        LocalImageAssetStore imageStore = new LocalImageAssetStore(Files.createTempDirectory("task-status-images"));
        ImageTools imageTools = new ImageTools(imageService, imageStore, artifacts,
                ImageInspectionService.unavailable(), taskStore, AiTraceLogger.disabled());
        ImageTaskStatusTools tools = new ImageTaskStatusTools(taskStore, imageTools, artifacts);
        var failed = taskStore.start("task-user", Operation.GENERATE, "一只戴蓝色围巾的小狗", "", 0);
        taskStore.fail("task-user", failed.taskId(), "图片服务暂时没有响应");

        String running = callback(tools, "get_running_tasks").call("{}");
        String checked = callback(tools, "check_image_task")
                .call("{\"taskId\":\"" + failed.taskId() + "\"}");
        String retried = callback(tools, "retry_last_image_task").call("{}");

        assertThat(running).contains("没有正在执行");
        assertThat(checked).contains(failed.taskId(), "失败", "图片服务暂时没有响应");
        assertThat(retried).contains("图片已生成", "任务编号", "已成功");
        assertThat(taskStore.recent("task-user", 8))
                .extracting(ImageTaskStatusStore.ImageTask::status)
                .containsExactly(Status.SUCCEEDED, Status.FAILED);
        assertThat(artifacts.finish()).singleElement().satisfies(artifact -> {
            assertThat(artifact.type()).isEqualTo(AiArtifact.Type.IMAGE);
            assertThat(artifact.assetId()).isEqualTo(imageStore.current("task-user").orElseThrow().assetId());
        });
        verify(imageService).generate(eq("task-user"), eq("一只戴蓝色围巾的小狗"));
    }

    private static ToolCallback callback(Object tools, String name) {
        return Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
