package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.ImageTask;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Operation;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Status;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 向模型公开当前微信用户最近图片生成/改图任务的真实本地执行状态。 */
@Component
public class ImageTaskStatusTools {
    private final ImageTaskStatusStore taskStore;
    private final ImageTools imageTools;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public ImageTaskStatusTools(ImageTaskStatusStore taskStore, ImageTools imageTools,
                                ToolArtifactCollector artifacts) {
        this(taskStore, imageTools, artifacts, AiTraceLogger.disabled());
    }

    @Autowired
    public ImageTaskStatusTools(ImageTaskStatusStore taskStore, ImageTools imageTools,
                                ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this.taskStore = taskStore;
        this.imageTools = imageTools;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "get_running_tasks", description = "当用户问图片是否还在生成、还有没有进行中的图片任务时调用。"
            + "只返回当前微信用户正在执行的真实图片任务；没有运行中任务时不要猜测云端状态。")
    public String getRunningTasks() {
        String userId = artifacts.userId();
        trace.toolCall("get_running_tasks", "current user image tasks");
        List<ImageTask> tasks = taskStore.running(userId);
        String result = tasks.isEmpty()
                ? "当前没有正在执行的图片任务。已完成的图片不会自动发送，可查询最近任务或让我发送结果图片。"
                : tasks.stream().map(this::describe).reduce((left, right) -> left + "\n" + right).orElseThrow();
        trace.toolResult("get_running_tasks", "count=" + tasks.size());
        return result;
    }

    @Tool(name = "check_image_task", description = "当用户询问刚才图片任务是否成功、失败原因、任务结果或指定任务编号时调用。"
            + "taskId 为空时查询当前微信用户最近一次图片任务；只能根据工具返回状态回答，不能猜测。")
    public String checkImageTask(
            @ToolParam(required = false, description = "可选图片任务编号，例如 imgtask_abc123def4567890；为空时查询最近任务。") String taskId
    ) {
        String userId = artifacts.userId();
        trace.toolCall("check_image_task", "task=" + safe(taskId));
        Optional<ImageTask> task = safe(taskId).isBlank() ? taskStore.latest(userId) : taskStore.find(userId, taskId);
        String result = task.map(this::describe)
                .orElse("当前用户没有可查询的图片任务，或该任务编号不属于当前会话。");
        trace.toolResult("check_image_task", compact(result));
        return result;
    }

    @Tool(name = "retry_last_image_task", description = "仅当用户明确要求重试刚才失败的图片生成或改图时调用。"
            + "它只会重复当前微信用户最近一次失败任务；没有失败任务、用户只是询问状态，或未明确要求重试时都不要调用。")
    public String retryLastImageTask() {
        String userId = artifacts.userId();
        trace.toolCall("retry_last_image_task", "current user latest failed image task");
        Optional<ImageTask> failed = taskStore.latestFailed(userId);
        if (failed.isEmpty()) {
            return "当前没有可重试的失败图片任务。请先查询最近任务状态，或让用户明确新的图片需求。";
        }
        String result = imageTools.retryFailedTask(failed.get());
        trace.toolResult("retry_last_image_task", compact(result));
        return result;
    }

    private String describe(ImageTask task) {
        String type = task.operation() == Operation.GENERATE ? "图片生成" : "图片修改";
        String status = switch (task.status()) {
            case RUNNING -> "执行中";
            case SUCCEEDED -> "已成功";
            case FAILED -> "失败";
        };
        StringBuilder result = new StringBuilder("图片任务 ").append(task.taskId())
                .append("：状态 ").append(status)
                .append("；类型 ").append(type)
                .append("；耗时 ").append(Math.max(0, task.elapsedMillis() / 1000)).append(" 秒");
        if (!task.sourceAssetId().isBlank()) {
            result.append("；原图 ").append(task.sourceAssetId()).append(" v").append(task.sourceVersion());
        }
        if (!task.resultAssetId().isBlank()) {
            result.append("；结果图片 ").append(task.resultAssetId()).append(" v").append(task.resultVersion());
        }
        if (task.status() == Status.FAILED) {
            result.append("；原因：").append(task.failureSummary().isBlank() ? "服务未返回可用图片" : task.failureSummary());
        }
        return result.append('。').toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private static String compact(String value) {
        String safe = safe(value).replaceAll("\\s+", " ");
        return safe.length() <= 200 ? safe : safe.substring(0, 200);
    }
}
