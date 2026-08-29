package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.service.ImageInspectionService;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ImageToolsAsyncTaskTest {

    @Test
    void returnsImmediatelyThenPersistsAndPublishesTheCompletedImageWithoutAnArtifact() throws Exception {
        AiImageGenerationService imageService = mock(AiImageGenerationService.class);
        when(imageService.generate(eq("async-user"), anyString()))
                .thenReturn(AiImageGenerationService.Result.image(new byte[]{1, 2, 3}));
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("async-user");
        ImageTaskStatusStore taskStore = new ImageTaskStatusStore();
        QueuedRunner runner = new QueuedRunner();
        AtomicReference<ImageTaskCompletionEvent> completion = new AtomicReference<>();
        LocalImageAssetStore store = new LocalImageAssetStore(Files.createTempDirectory("async-image-task"));
        ImageTools tools = new ImageTools(imageService, store, artifacts, ImageInspectionService.unavailable(),
                taskStore, runner, completion::set, AiTraceLogger.disabled());

        String response = tools.generateImage("一只戴蓝色围巾的小狗");
        String taskId = taskStore.latest("async-user").orElseThrow().taskId();

        assertThat(response).contains("图片任务已提交后台", taskId, "执行中");
        assertThat(taskStore.find("async-user", taskId)).get()
                .extracting(ImageTaskStatusStore.ImageTask::status)
                .isEqualTo(ImageTaskStatusStore.Status.RUNNING);
        assertThat(artifacts.finish()).isEmpty();
        verifyNoInteractions(imageService);

        runner.runNext();

        assertThat(taskStore.find("async-user", taskId)).get()
                .extracting(task -> task.status(), task -> task.resultAssetId(), task -> task.resultVersion())
                .containsExactly(ImageTaskStatusStore.Status.SUCCEEDED,
                        store.current("async-user").orElseThrow().assetId(), 1);
        assertThat(completion.get()).isNotNull();
        assertThat(completion.get().userId()).isEqualTo("async-user");
        assertThat(completion.get().taskId()).isEqualTo(taskId);
        assertThat(completion.get().imageBytes()).containsExactly(1, 2, 3);
    }

    private static final class QueuedRunner implements ImageTaskRunner {
        private final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public boolean submit(Runnable task) {
            queued.addLast(task);
            return true;
        }

        void runNext() {
            queued.removeFirst().run();
        }
    }
}
