 package com.wechatbot.fashion.schedule.service;

 import com.wechatbot.fashion.schedule.model.ScheduledTask;
 import com.wechatbot.fashion.schedule.model.TaskStatus;
 import com.wechatbot.fashion.schedule.model.TaskType;
 import com.wechatbot.fashion.schedule.repo.ScheduledTaskRepository;
 import org.junit.jupiter.api.Test;
 import org.springframework.beans.factory.annotation.Autowired;
 import org.springframework.boot.test.context.SpringBootTest;

 import java.time.LocalDateTime;
 import java.util.Optional;

 import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时任务调度器集成测试。
 *
 * <p>启动完整 Spring 上下文 + 真实 MySQL，测试一次性任务和 CRON 任务的创建与执行。
 * 命名带 {@code IntegrationTest} 后缀，由 unit profile 排除、integration profile 纳入。</p>
 */
@SpringBootTest(properties = {
        "ilink.enabled=false",
        "app.fashion.semantic.enabled=false",
        "app.fashion.reference.enabled=false"
})
@org.springframework.test.context.ActiveProfiles("test")
class DynamicTaskSchedulerIntegrationTest extends com.wechatbot.fashion.testing.IntegrationTestcontainers {

    @Autowired
    private DynamicTaskScheduler scheduler;

    @Autowired
    private ScheduledTaskRepository repository;

    @Test
    void createAndExecuteOnceTask() throws Exception {
        // 创建一个 2 秒后执行的一次性任务
        LocalDateTime fireAt = LocalDateTime.now().plusSeconds(2);
        ScheduledTask task = scheduler.createTask(
                "test-once-exec", null, TaskType.ONCE, null, fireAt,
                "echoTaskHandler", "{\"test\":\"once\"}"
        );
        assertThat(task.getId()).isPositive();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.WAITING);

        // 等待任务执行
        Thread.sleep(3000);

        // 验证任务已标记为 FINISHED
        Optional<ScheduledTask> refreshed = repository.findById(task.getId());
        assertThat(refreshed).isPresent();
        assertThat(refreshed.get().getStatus()).isEqualTo(TaskStatus.FINISHED);
    }

    @Test
    void createAndCancelCronTask() {
        // 创建一个每 5 秒执行的 CRON 任务
        ScheduledTask task = scheduler.createTask(
                "test-cron", null, TaskType.CRON, "0/5 * * * * ?", null,
                "echoTaskHandler", "{\"test\":\"cron\"}"
        );
        assertThat(task.getId()).isPositive();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.WAITING);
        assertThat(task.getCronExpr()).isEqualTo("0/5 * * * * ?");

        // 取消任务以清理
        scheduler.cancelTask(task.getId());

        // 验证已从 DB 删除
        assertThat(repository.findById(task.getId())).isEmpty();
    }

    @Test
    void cancelOnceTaskBeforeExecution() {
        // 创建一个 1 小时后执行的任务
        LocalDateTime fireAt = LocalDateTime.now().plusHours(1);
        ScheduledTask task = scheduler.createTask(
                "test-cancel-before", null, TaskType.ONCE, null, fireAt,
                "echoTaskHandler", "{}"
        );
        assertThat(task.getId()).isPositive();

        // 立即取消
        scheduler.cancelTask(task.getId());
        assertThat(repository.findById(task.getId())).isEmpty();
    }

    @Test
    void pauseAndResumeCronTask() {
        // 创建一个 CRON 任务
        ScheduledTask task = scheduler.createTask(
                "test-pause-resume", null, TaskType.CRON, "0 0 8 * * ?", null,
                "echoTaskHandler", "{}"
        );
        assertThat(task.getStatus()).isEqualTo(TaskStatus.WAITING);

        // 暂停
        scheduler.pauseTask(task.getId());
        ScheduledTask paused = repository.findById(task.getId()).orElseThrow();
        assertThat(paused.getStatus()).isEqualTo(TaskStatus.PAUSED);

        // 恢复
        scheduler.resumeTask(task.getId());
        ScheduledTask resumed = repository.findById(task.getId()).orElseThrow();
        assertThat(resumed.getStatus()).isEqualTo(TaskStatus.WAITING);

        // 清理
        scheduler.cancelTask(task.getId());
    }

    @Test
    void listAllTasks() {
        // 先获取当前任务数
        int before = scheduler.listAllTasks().size();

        // 创建一个新任务
        LocalDateTime fireAt = LocalDateTime.now().plusHours(2);
        scheduler.createTask(
                "test-list", null, TaskType.ONCE, null, fireAt,
                "echoTaskHandler", "{}"
        );

        // 总数应增加 1
        assertThat(scheduler.listAllTasks()).hasSize(before + 1);
    }

    @Test
    void rejectPastFireAt() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(5);
        try {
            scheduler.createTask(
                    "test-past", null, TaskType.ONCE, null, past,
                    "echoTaskHandler", "{}"
            );
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("future");
        }
    }
}
