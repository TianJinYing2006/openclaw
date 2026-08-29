package com.wechatbot.fashion.ai.tool;

import com.wechatbot.fashion.ai.orchestration.ToolRegistry;
import com.wechatbot.fashion.ai.service.ImageTaskStatusStore;
import com.wechatbot.fashion.ai.service.ImageTaskStatusStore.ImageTask;
import com.wechatbot.fashion.ai.service.ImageTaskStatusStore.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全量真实 Tool 冒烟验收。只有显式设置 FULL_LIVE_TOOLS_TEST=true 才会联网、生成媒体或写入测试数据。
 * 每个 ToolRegistry 白名单内的 Tool 都通过 ToolRegistry 调用一次；外部服务未配置时，正确返回友好提示也会记录下来。
 */
@SpringBootTest(properties = {"ilink.enabled=false", "app.ai.usage.enabled=false"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "FULL_LIVE_TOOLS_TEST", matches = "true")
class FullLiveToolRegistrySmokeTest {

    private static final Pattern TASK_ID = Pattern.compile("任务编号 ([^，）\\s]+)");

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private ToolArtifactCollector artifacts;

    @Autowired
    private ImageTaskStatusStore imageTaskStore;

    private final String userId = "full-live-tool-" + UUID.randomUUID();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    @AfterEach
    void clearArtifacts() {
        artifacts.discard();
    }

    @Test
    @Timeout(900)
    void invokesEveryRegisteredToolWithRepresentativeInputs() throws Exception {
        artifacts.begin(userId);

        // 图片 Tool 需要先建立实际上下文，后续调用使用本轮返回的真实 assetId。
        String image = call("generate_image", "一张极简扁平插画：白色背景中央有一个红色正方形，不包含文字。");
        String imageTaskId = requireTaskId(image);
        ImageTask generatedTask = awaitImageTask(imageTaskId);
        assertThat(generatedTask.status()).isEqualTo(Status.SUCCEEDED);
        String imageId = generatedTask.resultAssetId();
        assertThat(imageId).startsWith("img_");
        call("get_current_image");
        call("list_recent_images");
        call("inspect_image", imageId, "画面中是什么颜色和形状？");
        String revision = call("create_image_revision", imageId, "保持白色背景与正方形构图不变，把红色正方形改成蓝色。");
        ImageTask revisionTask = awaitImageTask(requireTaskId(revision));
        assertThat(revisionTask.status()).isEqualTo(Status.SUCCEEDED);
        call("restore_image_version", imageId, 1);

        // 天气与时间。
        call("get_current_weather", "北京");
        call("get_current_china_time");

        // 提醒类 Tool 返回用户可见文案；创建一个未来的 ONCE 任务后取消，避免污染数据。
        String onceAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(2)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        call("create_scheduled_agent_task", "到点提醒我喝水", "ONCE", onceAt, null, null);
        call("list_wechat_reminders");
        call("cancel_wechat_reminder", "00000000-0000-0000-0000-000000000000");

        // 穿搭多 Agent 管道入口（会真实调用 LLM 与 RAGFlow 检索，属于重型实时验证）
        call("fashion_consultant", "帮我搭一套适合海边度假的清爽穿搭");

        assertThat(results.keySet()).containsExactlyInAnyOrderElementsOf(
                registry.allToolMeta().stream().map(ToolRegistry.ToolMeta::name).toList());
        assertThat(failures).as("Tool invocation errors").isEmpty();
    }

    private String call(String toolName, Object... arguments) {
        ToolRegistry.ToolEntry entry = registry.find(toolName)
                .orElseThrow(() -> new AssertionError("未注册 Tool：" + toolName));
        try {
            Object response = entry.method().invoke(entry.bean(), arguments);
            String text = String.valueOf(response);
            if (text.isBlank() || "null".equals(text)) {
                failures.add(toolName + " returned blank result");
            }
            results.put(toolName, text);
            System.out.printf("FULL_LIVE_TOOL name=%s result=%s%n", toolName, summarize(text));
            return text;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getTargetException();
            failures.add(toolName + ": " + cause.getClass().getSimpleName() + ": " + safeMessage(cause));
            results.put(toolName, "THREW " + cause.getClass().getSimpleName());
            System.out.printf("FULL_LIVE_TOOL name=%s threw=%s%n", toolName, cause.getClass().getSimpleName());
            return "";
        } catch (ReflectiveOperationException | RuntimeException exception) {
            failures.add(toolName + ": " + exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            results.put(toolName, "THREW " + exception.getClass().getSimpleName());
            System.out.printf("FULL_LIVE_TOOL name=%s threw=%s%n", toolName, exception.getClass().getSimpleName());
            return "";
        }
    }

    private static String firstTaskId(String result) {
        Matcher matcher = TASK_ID.matcher(result == null ? "" : result);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String requireTaskId(String result) {
        String taskId = firstTaskId(result);
        assertThat(taskId).as("expected task ID in %s", summarize(result)).isNotBlank();
        return taskId;
    }

    private ImageTask awaitImageTask(String taskId) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(7);
        while (System.nanoTime() < deadline) {
            ImageTask task = imageTaskStore.find(userId, taskId).orElse(null);
            if (task != null && task.status() != Status.RUNNING) {
                return task;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("图片任务等待超时：" + taskId);
    }

    private static String summarize(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "no message" : summarize(message);
    }
}
