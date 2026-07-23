package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.ai.service.ImageInspectionService;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 给 Spring AI 暴露的生图能力；由模型在确实需要生成图片时自行选择调用。 */
@Component
public class ImageTools {
    private final AiImageGenerationService imageService;
    private final LocalImageAssetStore imageStore;
    private final ToolArtifactCollector artifacts;
    private final ImageInspectionService inspectionService;
    private final AiTraceLogger trace;

    public ImageTools(AiImageGenerationService imageService,
                      LocalImageAssetStore imageStore,
                      ToolArtifactCollector artifacts) {
        this(imageService, imageStore, artifacts, ImageInspectionService.unavailable(), AiTraceLogger.disabled());
    }

    public ImageTools(AiImageGenerationService imageService,
                      LocalImageAssetStore imageStore,
                      ToolArtifactCollector artifacts,
                      ImageInspectionService inspectionService) {
        this(imageService, imageStore, artifacts, inspectionService, AiTraceLogger.disabled());
    }

    @Autowired
    public ImageTools(AiImageGenerationService imageService,
                      LocalImageAssetStore imageStore,
                      ToolArtifactCollector artifacts,
                      ImageInspectionService inspectionService,
                      AiTraceLogger trace) {
        this.imageService = imageService;
        this.imageStore = imageStore;
        this.artifacts = artifacts;
        this.inspectionService = inspectionService;
        this.trace = trace;
    }

    @Tool(name = "generate_image", description = "当用户明确要求生成、画出、制作一张全新图片时调用。"
            + "prompt 要写成完整中文画面描述，包含主体、动作、风格、构图和限制；普通解释、分析或只讨论图片时不要调用。"
            + "每张新图会得到独立 image assetId 与 v1。")
    public String generateImage(
            @ToolParam(required = true, description = "完整中文画面描述，必须包含用户想要的主体和关键视觉细节。") String prompt
    ) {
        String userId = artifacts.userId();
        trace.toolCall("generate_image", "promptLength=" + safeLength(prompt));
        AiImageGenerationService.Result result = imageService.generate(userId, prompt);
        if (!result.hasImage()) {
            trace.toolResult("generate_image", "生成失败：" + result.errorMessage());
            return "图片生成失败：" + result.errorMessage();
        }
        StoredImage stored = imageStore.saveGenerated(userId, prompt, result.imageBytes(), result.remoteUrl());
        artifacts.add(AiArtifact.image(result.imageBytes(), "已生成图片", stored.assetId(), stored.version()));
        String toolResult = describe("图片已生成并会作为图片消息发送给用户", stored);
        trace.toolResult("generate_image", toolResult);
        return toolResult;
    }

    @Tool(name = "get_current_image", description = "当用户提到刚才、上一张、当前图片，或者准备修改、比较、回退图片前调用。"
            + "返回当前图片的 image assetId、版本、保存时的画面描述和来源。没有图片时应先请用户发送或生成。")
    public String getCurrentImage() {
        return imageStore.current(artifacts.userId())
                .map(value -> describe("当前图片", value) + "；保存时描述：" + value.prompt()
                        + optionalTags(value))
                .orElse("当前用户没有可操作的图片资源。请先让用户发送或生成图片。");
    }

    @Tool(name = "list_recent_images", description = "当用户说多个图片、那只猫、那个人、上一张以外的图片且指代不明确时调用。"
            + "列出当前用户最近图片的 assetId、版本、来源和保存时描述，模型据此先澄清或选择正确资源。")
    public String listRecentImages() {
        List<StoredImage> images = imageStore.recent(artifacts.userId(), 8);
        if (images.isEmpty()) {
            return "当前用户没有可用图片资源。";
        }
        return images.stream().map(value -> describe("图片", value) + "；描述：" + value.prompt()
                        + optionalTags(value))
                .reduce((left, right) -> left + "\n" + right).orElse("当前用户没有可用图片资源。");
    }

    @Tool(name = "inspect_image", description = "当用户的问题依赖某张已保存图片的真实视觉内容时调用，例如“图里的人穿什么”“把那只猫改成白色”。"
            + "先用 get_current_image 或 list_recent_images 确定 image assetId；此工具会将本地原图通过 Responses 视觉通道读取并返回描述，"
            + "不要根据编号或保存提示词猜测画面细节。")
    public String inspectImage(
            @ToolParam(required = true, description = "要查看的 image assetId，来自图片查询工具。") String assetId,
            @ToolParam(required = false, description = "希望识别的具体问题；空白时返回完整画面摘要。") String question
    ) {
        trace.toolCall("inspect_image", "asset=" + safe(assetId) + ", questionLength=" + safeLength(question));
        Optional<StoredImage> image = imageStore.latest(artifacts.userId(), assetId);
        if (image.isEmpty()) {
            String toolResult = "找不到图片资源：" + assetId + "。请先查询当前或最近图片。";
            trace.toolResult("inspect_image", toolResult);
            return toolResult;
        }
        try {
            String visualSummary = inspectionService.inspect(image.get(), question);
            imageStore.annotate(artifacts.userId(), assetId, visualSummary);
            String toolResult = describe("视觉识别对象", image.get()) + "\n识别结果：" + visualSummary
                    + "\n该识别摘要已登记到图片元数据，后续可用于定位这张图片。";
            trace.toolResult("inspect_image", toolResult);
            return toolResult;
        } catch (RuntimeException failure) {
            trace.toolFailure("inspect_image", failure);
            throw failure;
        }
    }

    @Tool(name = "create_image_revision", description = "当用户要求把某张已保存图片改成另一种颜色、风格、人物状态或构图时调用。"
            + "必须先 get_current_image 或 list_recent_images 选择 image assetId。该工具会把上一版原图以私有 OSS 的短时参考地址送入图生图接口；"
            + "如果新要求依赖原图中具体人物、物体或颜色，必须先调用 inspect_image 获取视觉描述。")
    public String createImageRevision(
            @ToolParam(required = true, description = "要修改的 image assetId，必须来自图片查询工具。") String assetId,
            @ToolParam(required = true, description = "新的完整画面描述，需包含保留什么、改变什么以及最终风格。") String prompt
    ) {
        String userId = artifacts.userId();
        trace.toolCall("create_image_revision", "asset=" + safe(assetId) + ", promptLength=" + safeLength(prompt));
        Optional<StoredImage> base = imageStore.latest(userId, assetId);
        if (base.isEmpty()) {
            String toolResult = "找不到图片资源：" + assetId + "。请先查询当前或最近图片。";
            trace.toolResult("create_image_revision", toolResult);
            return toolResult;
        }
        String completePrompt = "保留原图中未被用户要求修改的主体、构图与细节。上一版保存描述：" + base.get().prompt()
                + optionalTags(base.get()) + "。新的修改要求：" + prompt;
        String referenceUrl;
        try {
            referenceUrl = imageStore.signedReadUrl(base.get());
        } catch (RuntimeException exception) {
            trace.toolFailure("create_image_revision", exception);
            return "图片新版本生成失败：无法取得原图参考地址";
        }
        AiImageGenerationService.Result result = imageService.revise(userId, completePrompt, referenceUrl);
        if (!result.hasImage()) {
            String toolResult = "图片新版本生成失败：" + result.errorMessage();
            trace.toolResult("create_image_revision", toolResult);
            return toolResult;
        }
        StoredImage stored = imageStore.saveRevision(userId, assetId, completePrompt, result.imageBytes(), result.remoteUrl());
        artifacts.add(AiArtifact.image(result.imageBytes(), "图片新版本", stored.assetId(), stored.version()));
        String toolResult = describe("图片已生成新版本并会发送给用户", stored);
        trace.toolResult("create_image_revision", toolResult);
        return toolResult;
    }

    @Tool(name = "restore_image_version", description = "仅当用户明确要求回到某个历史图片版本、撤销最近一次图片修改时调用。"
            + "先使用 get_current_image 或 list_recent_images 取得 assetId；恢复会把历史版本复制成一个新的当前版本，不删除历史版本。")
    public String restoreImageVersion(
            @ToolParam(required = true, description = "目标 image assetId。") String assetId,
            @ToolParam(required = true, description = "要恢复的历史版本号，例如 1。") int targetVersion
    ) {
        trace.toolCall("restore_image_version", "asset=" + safe(assetId) + ", targetVersion=" + targetVersion);
        try {
            StoredImage stored = imageStore.restore(artifacts.userId(), assetId, targetVersion);
            byte[] bytes = imageStore.readBytes(stored);
            artifacts.add(AiArtifact.image(bytes, "恢复后的图片", stored.assetId(), stored.version()));
            String toolResult = describe("已恢复并会作为新版本发送给用户", stored) + "（内容来自历史 v" + targetVersion + "）";
            trace.toolResult("restore_image_version", toolResult);
            return toolResult;
        } catch (RuntimeException exception) {
            trace.toolFailure("restore_image_version", exception);
            return "图片恢复失败：" + (exception.getMessage() == null ? "请先查询图片版本" : exception.getMessage());
        }
    }

    private static String describe(String action, StoredImage stored) {
        return action + "：图片编号 " + stored.assetId() + "，版本 v" + stored.version()
                + "，来源 " + stored.source() + "。";
    }

    private static String optionalTags(StoredImage stored) {
        return stored.tags() == null || stored.tags().isBlank() ? "" : "；视觉摘要：" + stored.tags();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private static int safeLength(String value) {
        return safe(value).length();
    }
}
