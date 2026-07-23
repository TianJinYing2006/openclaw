package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.bot.document.DocumentRenderer;
import com.example.ykdsummer.bot.document.DocumentTextExtractor;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore.StoredDocument;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 让模型以可审计的方式处理文档。每个写操作都会登记资源 ID 和版本，并把真实文件交回微信层。
 */
@Component
public class DocumentTools {
    private static final int MAX_TOOL_PREVIEW_CHARS = 30_000;

    private final LocalDocumentAssetStore store;
    private final DocumentRenderer renderer;
    private final DocumentTextExtractor extractor;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public DocumentTools(LocalDocumentAssetStore store, DocumentTextExtractor extractor,
                         ToolArtifactCollector artifacts) {
        this(store, new DocumentRenderer(), extractor, artifacts, AiTraceLogger.disabled());
    }

    @Autowired
    public DocumentTools(LocalDocumentAssetStore store, DocumentTextExtractor extractor,
                         ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this(store, new DocumentRenderer(), extractor, artifacts, trace);
    }

    DocumentTools(LocalDocumentAssetStore store, DocumentRenderer renderer, DocumentTextExtractor extractor,
                  ToolArtifactCollector artifacts) {
        this(store, renderer, extractor, artifacts, AiTraceLogger.disabled());
    }

    DocumentTools(LocalDocumentAssetStore store, DocumentRenderer renderer, DocumentTextExtractor extractor,
                  ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this.store = store;
        this.renderer = renderer;
        this.extractor = extractor;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "create_document", description = "当用户明确要求新建报告、Word、Excel、PDF 或 TXT 文件时调用。"
            + "必须提供可直接写入文件的完整 content，format 只能是 docx、xlsx、pdf、txt。"
            + "普通聊天、只给建议或只分析上传文件时不要调用。")
    public String createDocument(
            @ToolParam(required = true, description = "文件标题，例如 项目周报；不要含路径。") String title,
            @ToolParam(required = true, description = "输出格式：docx、xlsx、pdf 或 txt。") String format,
            @ToolParam(required = true, description = "完整文件正文。xlsx 使用 CSV 形式；不可只写提纲。") String content
    ) {
        trace.toolCall("create_document", "format=" + safe(format) + ", titleLength=" + safe(title).length());
        try {
            byte[] bytes = renderer.render(format, content);
            StoredDocument stored = store.create(artifacts.userId(), title, format, bytes, "模型创建：" + summarize(content));
            emit(stored);
            String result = describe("已创建", stored);
            trace.toolResult("create_document", result);
            return result;
        } catch (RuntimeException exception) {
            return "文档创建失败：" + safeMessage(exception);
        }
    }

    @Tool(name = "get_current_document", description = "当用户提到刚才、上一份、当前文档，或需要修改/回退文档前调用。"
            + "返回当前文档的资源 ID、当前版本、历史版本和正文预览。没有当前文档时先询问用户上传或新建。")
    public String getCurrentDocument() {
        trace.toolCall("get_current_document", "current user document query");
        Optional<StoredDocument> current = store.current(artifacts.userId());
        if (current.isEmpty()) {
            return "当前用户没有可操作的文档资源。请先让用户上传或创建一份文档。";
        }
        StoredDocument document = current.get();
        List<StoredDocument> versions = store.versions(artifacts.userId(), document.assetId());
        String history = versions.stream().map(item -> "v" + item.version() + "(" + item.summary() + ")")
                .collect(Collectors.joining("、"));
        String content = extractor.extract(document.format(), store.readBytes(document)).orElse("[该格式无法提取正文]");
        if (content.length() > MAX_TOOL_PREVIEW_CHARS) {
            content = content.substring(0, MAX_TOOL_PREVIEW_CHARS) + "\n[正文过长，已截断]";
        }
        String result = describe("当前文档", document) + "；版本历史：" + history + "。\n正文预览：\n" + content;
        trace.toolResult("get_current_document", describe("当前文档", document));
        return result;
    }

    @Tool(name = "replace_document_content", description = "当用户要求修改、润色、重写或补充当前文档正文时调用。"
            + "必须先调用 get_current_document 获取 assetId、当前格式与正文，再把修改后的完整 content 传入。"
            + "仅转换格式、不改变正文时必须改用 convert_document_format。该操作会创建同一文档的新版本，不会覆盖旧版本。")
    public String replaceDocumentContent(
            @ToolParam(required = true, description = "要修改的 document assetId，必须来自 get_current_document。") String assetId,
            @ToolParam(required = true, description = "修改后的完整文档正文；不能只写修改建议。") String content,
            @ToolParam(required = false, description = "目标格式 docx、xlsx、pdf、txt；留空则沿用当前格式。") String format,
            @ToolParam(required = true, description = "本次修改摘要，例如 将标题改为暑期计划并补充预算。") String changeSummary
    ) {
        trace.toolCall("replace_document_content", "asset=" + safe(assetId) + ", format=" + safe(format));
        try {
            StoredDocument current = store.current(artifacts.userId())
                    .filter(value -> value.assetId().equals(assetId))
                    .orElseGet(() -> store.find(artifacts.userId(), assetId, latestVersion(assetId))
                            .orElseThrow(() -> new IllegalArgumentException("找不到要修改的文档资源")));
            String targetFormat = format == null || format.isBlank() ? current.format() : format;
            byte[] bytes = renderer.render(targetFormat, content);
            StoredDocument stored = store.revise(artifacts.userId(), assetId, current.fileName(), targetFormat, bytes,
                    safe(changeSummary));
            emit(stored);
            String result = describe("文档已生成新版本", stored);
            trace.toolResult("replace_document_content", result);
            return result;
        } catch (RuntimeException exception) {
            return "文档修改失败：" + safeMessage(exception);
        }
    }

    @Tool(name = "convert_document_format", description = "当用户只要求把当前 Word、Excel、PDF 或 TXT 转成另一种支持格式，"
            + "并且不要求修改正文时调用。必须先调用 get_current_document 获取 assetId。"
            + "该工具会真实生成新文件和新版本；不能保留 Word 原排版、嵌入图片或复杂表格时必须在最终答复中如实说明。"
            + "targetFormat 只能是 docx、xlsx、pdf、txt。")
    public String convertDocumentFormat(
            @ToolParam(required = true, description = "要转换的 document assetId，必须来自 get_current_document。") String assetId,
            @ToolParam(required = true, description = "目标格式：docx、xlsx、pdf 或 txt。") String targetFormat
    ) {
        trace.toolCall("convert_document_format", "asset=" + safe(assetId) + ", format=" + safe(targetFormat));
        try {
            StoredDocument current = requireDocument(assetId);
            String content = extractor.extract(current.format(), store.readBytes(current))
                    .orElseThrow(() -> new IllegalArgumentException("当前文档无法提取正文，暂时不能安全转换格式"));
            byte[] bytes = renderer.render(targetFormat, content);
            StoredDocument stored = store.revise(artifacts.userId(), assetId, current.fileName(), targetFormat, bytes,
                    "格式转换：" + current.format() + " -> " + safe(targetFormat));
            emit(stored);
            String result = describe("文档已转换", stored) + "（由 " + current.format() + " 转换）";
            trace.toolResult("convert_document_format", result);
            return result;
        } catch (RuntimeException exception) {
            return "文档转换失败：" + safeMessage(exception);
        }
    }

    @Tool(name = "restore_document_version", description = "仅当用户明确要求回到某个历史版本、撤销最近修改时调用。"
            + "先调用 get_current_document 查看 assetId 和版本历史；恢复后会生成一个新的当前版本，历史版本不会被删除。")
    public String restoreDocumentVersion(
            @ToolParam(required = true, description = "目标 document assetId，来自 get_current_document。") String assetId,
            @ToolParam(required = true, description = "要恢复的历史版本号，例如 1。") int targetVersion
    ) {
        trace.toolCall("restore_document_version", "asset=" + safe(assetId) + ", targetVersion=" + targetVersion);
        try {
            StoredDocument stored = store.restore(artifacts.userId(), assetId, targetVersion);
            emit(stored);
            String result = describe("已恢复并生成新版本", stored) + "（内容来自历史 v" + targetVersion + "）";
            trace.toolResult("restore_document_version", result);
            return result;
        } catch (RuntimeException exception) {
            return "文档恢复失败：" + safeMessage(exception);
        }
    }

    private int latestVersion(String assetId) {
        return store.versions(artifacts.userId(), assetId).stream()
                .mapToInt(StoredDocument::version).max()
                .orElseThrow(() -> new IllegalArgumentException("找不到要修改的文档资源"));
    }

    private StoredDocument requireDocument(String assetId) {
        return store.current(artifacts.userId())
                .filter(value -> value.assetId().equals(assetId))
                .orElseGet(() -> store.find(artifacts.userId(), assetId, latestVersion(assetId))
                        .orElseThrow(() -> new IllegalArgumentException("找不到要操作的文档资源")));
    }

    private void emit(StoredDocument document) {
        artifacts.add(AiArtifact.document(store.readBytes(document), document.fileName(), describe("文档", document),
                document.assetId(), document.version()));
    }

    private static String describe(String action, StoredDocument document) {
        return action + "：文档编号 " + document.assetId() + "，版本 v" + document.version()
                + "，格式 " + document.format() + "，文件 " + document.fileName();
    }

    private static String summarize(String text) {
        String value = safe(text).replaceAll("\\s+", " ");
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "请检查格式和内容后重试" : message;
    }
}
