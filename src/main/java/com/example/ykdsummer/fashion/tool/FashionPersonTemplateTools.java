package com.example.ykdsummer.fashion.tool;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.application.FashionPersonTemplateService;
import com.example.ykdsummer.fashion.domain.FashionPersonTemplate;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** User-scoped tools for saving and selecting the source image used by future virtual try-on. */
@Component
@ConditionalOnBean(FashionPersonTemplateService.class)
public class FashionPersonTemplateTools implements AiTool {
    private final FashionPersonTemplateService templates;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public FashionPersonTemplateTools(FashionPersonTemplateService templates, ToolArtifactCollector artifacts,
                                      AiTraceLogger trace) {
        this.templates = templates;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "save_person_tryon_template", description = "仅当用户明确要求把已上传的人物全身照设为试衣/换装模板时调用。"
            + "必须先通过图片工具获取当前图片或指定图片编号。系统只检查构图是否适合试衣，不推断身份、年龄、性别、"
            + "体重或身体尺寸；照片不满足单人、头到脚完整、正面或近正面、清晰无遮挡时会要求重拍。")
    public String savePersonTryonTemplate(
            @ToolParam(description = "人物图片编号，必须来自当前或已保存的 img_ 图片。") String imageAssetId,
            @ToolParam(required = false, description = "图片版本；为空时使用最新版本。") Integer imageVersion,
            @ToolParam(required = false, description = "模板名称，例如 通勤全身照；为空时自动命名。") String templateName
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("save_person_tryon_template", "hasImage=true");
        try {
            FashionPersonTemplateService.SaveResult result = templates.saveFromPhoto(userId, imageAssetId, imageVersion, templateName);
            if (!result.saved()) {
                String message = "这张照片暂不适合作为试衣模板：" + safe(result.assessment().retakeGuidance());
                trace.toolResult("save_person_tryon_template", message);
                return message;
            }
            String message = "已保存并启用试衣模板 #" + result.template().id() + "：" + displayName(result.template())
                    + "。后续生成上身效果会优先使用它。";
            trace.toolResult("save_person_tryon_template", message);
            return message;
        } catch (IllegalArgumentException failure) {
            return failed("save_person_tryon_template", failure, "保存试衣模板失败：请确认图片属于当前用户。");
        } catch (RuntimeException failure) {
            return failed("save_person_tryon_template", failure, "保存试衣模板失败，请稍后重试。");
        }
    }

    @Tool(name = "list_person_tryon_templates", description = "当用户询问已保存的试衣模特图、人物模板，"
            + "或希望更换试衣模板前调用。只能返回当前微信用户自己的模板。")
    public String listPersonTryonTemplates() {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            List<FashionPersonTemplate> values = templates.list(userId, 12);
            String message = values.isEmpty() ? "当前还没有已保存的试衣模板。"
                    : values.stream().map(FashionPersonTemplateTools::describe).collect(java.util.stream.Collectors.joining("\n", "试衣模板：\n", ""));
            trace.toolResult("list_person_tryon_templates", message);
            return message;
        } catch (RuntimeException failure) {
            return failed("list_person_tryon_templates", failure, "读取试衣模板失败，请稍后重试。");
        }
    }

    @Tool(name = "select_person_tryon_template", description = "仅当用户明确指定要切换到某个已保存的试衣人物模板时调用。"
            + "templateId 必须来自 list_person_tryon_templates 的完整编号。")
    public String selectPersonTryonTemplate(@ToolParam(description = "完整人物模板编号 UUID") String templateId) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        try {
            FashionPersonTemplate selected = templates.activate(userId, templateId);
            String message = "已启用试衣模板 #" + selected.id() + "：" + displayName(selected) + "。";
            trace.toolResult("select_person_tryon_template", message);
            return message;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return failed("select_person_tryon_template", failure, "切换试衣模板失败：请确认模板编号正确且照片可用。");
        } catch (RuntimeException failure) {
            return failed("select_person_tryon_template", failure, "切换试衣模板失败，请稍后重试。");
        }
    }

    private String currentUser() {
        String userId = artifacts.userId();
        return userId == null || userId.isBlank() || "unknown".equals(userId) ? null : userId;
    }
    private String failed(String name, RuntimeException failure, String message) {
        trace.toolFailure(name, failure);
        return message;
    }
    private static String unavailable() { return "当前会话身份不可用，暂时不能管理试衣模板。"; }
    private static String displayName(FashionPersonTemplate template) {
        return safe(template.displayName()).isBlank() ? "人物模板" : safe(template.displayName());
    }
    private static String describe(FashionPersonTemplate template) {
        return "- #" + template.id() + " | " + displayName(template) + (template.active() ? " | 当前启用" : "")
                + " | 图片 " + template.sourceAssetId() + " v" + template.sourceAssetVersion();
    }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
