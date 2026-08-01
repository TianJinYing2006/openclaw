package com.example.ykdsummer.fashion.tool;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.application.FashionVirtualTryOnService;
import com.example.ykdsummer.fashion.domain.FashionTryOnTask;
import com.example.ykdsummer.fashion.domain.FashionTryOnTaskStatus;
import java.util.Optional;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Agent boundary for a user-approved wardrobe item plus active person-template virtual try-on. */
@Component
@ConditionalOnBean(FashionVirtualTryOnService.class)
public class FashionTryOnTools implements AiTool {
    private final FashionVirtualTryOnService tryOn;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public FashionTryOnTools(FashionVirtualTryOnService tryOn, ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this.tryOn = tryOn;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "virtual_try_on_wardrobe_item", description = "仅当用户明确同意把某件已确认入衣橱的单品穿到当前试衣模板上时调用。"
            + "调用前应先用 search_wardrobe，或 select_wardrobe_preview_item，确认衣橱单品的内部编号；"
            + "不能因为用户只是询问搭配或展示衣橱就自动调用。"
            + "系统使用当前启用的人物模板和该单品的主图片，在后台生成，完成后自动发回微信。")
    public String virtualTryOnWardrobeItem(
            @ToolParam(description = "来自 search_wardrobe 或 select_wardrobe_preview_item 的内部 wardrobeItemId。") long wardrobeItemId
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("virtual_try_on_wardrobe_item", "wardrobeItem=" + wardrobeItemId);
        try {
            FashionTryOnTask task = tryOn.submit(userId, wardrobeItemId);
            String message = task.status() == FashionTryOnTaskStatus.PROCESSING
                    ? "这件衣服正在生成上身效果，完成后会自动发给你。"
                    : "已开始生成这件衣服的上身效果。后台完成后会自动把图片发给你。";
            trace.toolResult("virtual_try_on_wardrobe_item", message);
            return message;
        } catch (IllegalArgumentException failure) {
            return failed("virtual_try_on_wardrobe_item", failure,
                    "暂时不能试穿这件衣服：请先确认它已加入当前用户的衣橱并且有单品展示图。" );
        } catch (IllegalStateException failure) {
            return failed("virtual_try_on_wardrobe_item", failure,
                    "还不能开始试衣：请先上传并启用一张清晰的全身人物模板。" );
        } catch (RuntimeException failure) {
            return failed("virtual_try_on_wardrobe_item", failure, "提交试衣任务失败，请稍后重试。" );
        }
    }

    @Tool(name = "check_virtual_tryon_status", description = "当用户询问上身效果是否完成、试衣是否仍在生成、或刚才的试衣失败原因时调用。"
            + "只查询当前微信用户最近一次虚拟试衣任务，绝不能猜测状态。")
    public String checkVirtualTryOnStatus() {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("check_virtual_tryon_status", "latest");
        try {
            Optional<FashionTryOnTask> task = tryOn.latest(userId);
            String message = task.map(this::describe).orElse("当前没有已提交的上身试衣任务。");
            trace.toolResult("check_virtual_tryon_status", message);
            return message;
        } catch (RuntimeException failure) {
            return failed("check_virtual_tryon_status", failure, "读取试衣任务状态失败，请稍后再试。" );
        }
    }

    private String describe(FashionTryOnTask task) {
        return switch (task.status()) {
            case SUBMITTED -> "上身效果已进入后台队列，马上会开始生成；完成后会自动发图。";
            case PROCESSING -> "上身效果正在后台生成，完成后会自动发图。";
            case SUCCEEDED -> "最近一次上身效果已生成完成并保存；若没有收到图片，可让我重新发送最近图片。";
            case FAILED -> "最近一次上身效果没有生成成功：" + safe(task.failureSummary()) + "。可以重新试一次。";
        };
    }

    private String currentUser() {
        String userId = artifacts.userId();
        return userId == null || userId.isBlank() || "unknown".equals(userId) ? null : userId;
    }
    private String failed(String name, RuntimeException failure, String message) {
        trace.toolFailure(name, failure);
        return message;
    }
    private static String unavailable() { return "当前会话身份不可用，暂时不能使用个人试衣功能。"; }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
