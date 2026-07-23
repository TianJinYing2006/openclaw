package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Operation;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Status;
import org.junit.jupiter.api.Test;

class ImageTaskStatusStoreTest {

    @Test
    void tracksRunningSuccessAndFailureWithoutCrossUserLeakage() {
        ImageTaskStatusStore store = new ImageTaskStatusStore();
        var running = store.start("task-user-a", Operation.GENERATE, "一只小狗", "", 0);
        var failed = store.start("task-user-a", Operation.REVISION, "改成蓝色", "img_source", 2);
        store.fail("task-user-a", failed.taskId(), "图片编辑暂时没有响应");
        store.succeed("task-user-a", running.taskId(), "img_result", 1);
        store.start("task-user-b", Operation.GENERATE, "另一张图", "", 0);

        assertThat(store.running("task-user-a")).isEmpty();
        assertThat(store.find("task-user-a", running.taskId())).get()
                .extracting(task -> task.status(), task -> task.resultAssetId(), task -> task.resultVersion())
                .containsExactly(Status.SUCCEEDED, "img_result", 1);
        assertThat(store.latestFailed("task-user-a")).get()
                .extracting(task -> task.status(), task -> task.sourceAssetId(), task -> task.sourceVersion(),
                        task -> task.failureSummary())
                .containsExactly(Status.FAILED, "img_source", 2, "图片编辑暂时没有响应");
        assertThat(store.recent("task-user-b", 8)).singleElement()
                .extracting(task -> task.status())
                .isEqualTo(Status.RUNNING);
    }
}
