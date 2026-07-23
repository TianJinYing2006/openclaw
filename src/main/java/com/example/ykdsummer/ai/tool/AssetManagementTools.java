package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore.StoredDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 统一管理当前微信用户已保存的图片和文档资产。 */
@Component
public class AssetManagementTools {
    private final LocalImageAssetStore imageStore;
    private final LocalDocumentAssetStore documentStore;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public AssetManagementTools(LocalImageAssetStore imageStore,
                                LocalDocumentAssetStore documentStore,
                                ToolArtifactCollector artifacts) {
        this(imageStore, documentStore, artifacts, AiTraceLogger.disabled());
    }

    @Autowired
    public AssetManagementTools(LocalImageAssetStore imageStore,
                                LocalDocumentAssetStore documentStore,
                                ToolArtifactCollector artifacts,
                                AiTraceLogger trace) {
        this.imageStore = imageStore;
        this.documentStore = documentStore;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "list_recent_assets", description = "当用户说刚才的文件/图片、上一张、最近生成的内容，"
            + "或无法确定目标资产时调用。列出当前微信用户最近图片和文档的编号、版本、类型和摘要；"
            + "模型应据此选择目标或向用户澄清，不要猜测。")
    public String listRecentAssets(
            @ToolParam(required = false, description = "资产类型：image、document 或 all；默认 all。") String type
    ) {
        String userId = artifacts.userId();
        trace.toolCall("list_recent_assets", "type=" + safe(type));
        String normalized = normalizeType(type);
        StringBuilder result = new StringBuilder();
        if (!"document".equals(normalized)) {
            List<StoredImage> images = imageStore.recent(userId, 8);
            appendImages(result, images);
        }
        if (!"image".equals(normalized)) {
            List<StoredDocument> documents = documentStore.recent(userId, 8);
            appendDocuments(result, documents);
        }
        if (result.isEmpty()) {
            return "当前用户还没有可用的图片或文档资产。";
        }
        String value = result.toString().strip();
        trace.toolResult("list_recent_assets", compact(value));
        return value;
    }

    @Tool(name = "select_asset", description = "当用户明确指定某个 image/document assetId，"
            + "或模型已通过 list_recent_assets 找到目标后调用。该工具只把资产设为当前操作对象，不发送文件、不修改内容。")
    public String selectAsset(
            @ToolParam(required = true, description = "资产编号，例如 img_abcd1234ef56 或 doc_abcd1234ef56。") String assetId,
            @ToolParam(required = false, description = "目标版本；省略或小于 1 时选择该资产最新版本。") Integer version
    ) {
        String userId = artifacts.userId();
        trace.toolCall("select_asset", "asset=" + safe(assetId) + ", version=" + version);
        if (isImage(assetId)) {
            Optional<StoredImage> selected = selectImage(userId, assetId, version);
            String result = selected.map(value -> describeImage("已选择当前图片", value))
                    .orElse("找不到图片资源：" + safe(assetId));
            trace.toolResult("select_asset", result);
            return result;
        }
        if (isDocument(assetId)) {
            Optional<StoredDocument> selected = selectDocument(userId, assetId, version);
            String result = selected.map(value -> describeDocument("已选择当前文档", value))
                    .orElse("找不到文档资源：" + safe(assetId));
            trace.toolResult("select_asset", result);
            return result;
        }
        return "资产编号无效。图片编号以 img_ 开头，文档编号以 doc_ 开头。";
    }

    @Tool(name = "describe_asset", description = "当用户询问当前图片/文档是什么、编号是多少、版本是多少，"
            + "或需要在操作前确认资产时调用。assetId 为空时返回当前图片和当前文档摘要。")
    public String describeAsset(
            @ToolParam(required = false, description = "可选资产编号；为空时描述当前图片和当前文档。") String assetId
    ) {
        String userId = artifacts.userId();
        trace.toolCall("describe_asset", "asset=" + safe(assetId));
        String result;
        if (safe(assetId).isBlank()) {
            result = describeCurrent(userId);
        } else if (isImage(assetId)) {
            result = imageStore.latest(userId, assetId)
                    .map(value -> describeImage("图片", value))
                    .orElse("找不到图片资源：" + safe(assetId));
        } else if (isDocument(assetId)) {
            result = latestDocument(userId, assetId)
                    .map(value -> describeDocument("文档", value))
                    .orElse("找不到文档资源：" + safe(assetId));
        } else {
            result = "资产编号无效。图片编号以 img_ 开头，文档编号以 doc_ 开头。";
        }
        trace.toolResult("describe_asset", compact(result));
        return result;
    }

    @Tool(name = "resend_asset", description = "当用户说重新发、再发一次、我没收到、把刚才那张图/那个文件发来时调用。"
            + "该工具会读取当前用户已保存的图片或文档字节，并作为微信图片/文件消息重新发送；不会重新生成内容。")
    public String resendAsset(
            @ToolParam(required = true, description = "要重发的资产编号，例如 img_abcd1234ef56 或 doc_abcd1234ef56。") String assetId,
            @ToolParam(required = false, description = "目标版本；省略或小于 1 时重发该资产最新版本。") Integer version
    ) {
        String userId = artifacts.userId();
        trace.toolCall("resend_asset", "asset=" + safe(assetId) + ", version=" + version);
        if (isImage(assetId)) {
            Optional<StoredImage> image = selectImage(userId, assetId, version);
            if (image.isEmpty()) {
                return "找不到图片资源：" + safe(assetId);
            }
            StoredImage stored = image.get();
            artifacts.add(AiArtifact.image(imageStore.readBytes(stored), "重新发送图片", stored.assetId(), stored.version()));
            String result = describeImage("图片会重新发送给用户", stored);
            trace.toolResult("resend_asset", result);
            return result;
        }
        if (isDocument(assetId)) {
            Optional<StoredDocument> document = selectDocument(userId, assetId, version);
            if (document.isEmpty()) {
                return "找不到文档资源：" + safe(assetId);
            }
            StoredDocument stored = document.get();
            artifacts.add(AiArtifact.document(documentStore.readBytes(stored), stored.fileName(),
                    describeDocument("重新发送文档", stored), stored.assetId(), stored.version()));
            String result = describeDocument("文档会重新发送给用户", stored);
            trace.toolResult("resend_asset", result);
            return result;
        }
        return "资产编号无效。图片编号以 img_ 开头，文档编号以 doc_ 开头。";
    }

    private Optional<StoredImage> selectImage(String userId, String assetId, Integer version) {
        int requested = version == null ? 0 : version;
        Optional<StoredImage> target = requested < 1 ? imageStore.latest(userId, assetId)
                : imageStore.find(userId, assetId, requested);
        return target.flatMap(value -> imageStore.selectCurrent(userId, value.assetId(), value.version()));
    }

    private Optional<StoredDocument> selectDocument(String userId, String assetId, Integer version) {
        int requested = version == null ? 0 : version;
        Optional<StoredDocument> target = requested < 1 ? latestDocument(userId, assetId)
                : documentStore.find(userId, assetId, requested);
        return target.flatMap(value -> documentStore.selectCurrent(userId, value.assetId(), value.version()));
    }

    private Optional<StoredDocument> latestDocument(String userId, String assetId) {
        List<StoredDocument> versions = documentStore.versions(userId, assetId);
        return versions.isEmpty() ? Optional.empty() : Optional.of(versions.getLast());
    }

    private String describeCurrent(String userId) {
        String image = imageStore.current(userId).map(value -> describeImage("当前图片", value))
                .orElse("当前没有图片。");
        String document = documentStore.current(userId).map(value -> describeDocument("当前文档", value))
                .orElse("当前没有文档。");
        return image + "\n" + document;
    }

    private static void appendImages(StringBuilder result, List<StoredImage> images) {
        if (images.isEmpty()) {
            return;
        }
        result.append("最近图片：\n");
        images.forEach(value -> result.append("- ").append(describeImage("图片", value)).append('\n'));
    }

    private static void appendDocuments(StringBuilder result, List<StoredDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        result.append("最近文档：\n");
        documents.forEach(value -> result.append("- ").append(describeDocument("文档", value)).append('\n'));
    }

    private static String describeImage(String action, StoredImage image) {
        String tags = image.tags() == null || image.tags().isBlank() ? "" : "；视觉摘要：" + image.tags();
        return action + "：编号 " + image.assetId() + "，版本 v" + image.version()
                + "，来源 " + image.source() + "；描述：" + image.prompt() + tags;
    }

    private static String describeDocument(String action, StoredDocument document) {
        return action + "：编号 " + document.assetId() + "，版本 v" + document.version()
                + "，格式 " + document.format() + "，文件 " + document.fileName()
                + "；摘要：" + document.summary();
    }

    private static boolean isImage(String assetId) {
        return safe(assetId).startsWith("img_");
    }

    private static boolean isDocument(String assetId) {
        return safe(assetId).startsWith("doc_");
    }

    private static String normalizeType(String value) {
        String safe = safe(value).toLowerCase();
        return switch (safe) {
            case "image", "images", "图片" -> "image";
            case "document", "documents", "file", "files", "文档", "文件" -> "document";
            default -> "all";
        };
    }

    private static String compact(String value) {
        String safe = safe(value).replaceAll("\\s+", " ");
        return safe.length() <= 200 ? safe : safe.substring(0, 200);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
