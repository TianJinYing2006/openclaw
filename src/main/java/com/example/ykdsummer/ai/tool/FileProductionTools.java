package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.bot.document.DocumentRenderer;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore.StoredDocument;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 生成独立附件的交付型 Tool。
 *
 * <p>与当前文档修改不同：它不会读取、覆盖或转换已有文档，而是把模型已组织好的完整正文生产为一
 * 个新的文件资产，再交给现有 iLink 文件回复链路。</p>
 */
@Component
public class FileProductionTools {
    private final LocalDocumentAssetStore store;
    private final DocumentRenderer renderer;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public FileProductionTools(LocalDocumentAssetStore store, ToolArtifactCollector artifacts) {
        this(store, new DocumentRenderer(), artifacts, AiTraceLogger.disabled());
    }

    @Autowired
    public FileProductionTools(LocalDocumentAssetStore store, ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this(store, new DocumentRenderer(), artifacts, trace);
    }

    FileProductionTools(LocalDocumentAssetStore store, DocumentRenderer renderer,
                        ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this.store = store;
        this.renderer = renderer;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "produce_file", description = "当用户明确要求把当前整理好的内容作为一个独立附件交付时调用，"
            + "尤其是用户指定文件名，或需要 PPT、Markdown、HTML、CSV、JSON、XML 等文件时。"
            + "必须提供完整 content、真实 fileName 和 format。该工具会创建全新的文件资产并作为微信附件发送；"
            + "普通聊天、只给建议、修改当前文档或仅转换已有文档格式时不要调用。")
    public String produceFile(
            @ToolParam(required = true, description = "最终文件名，例如 项目周报.docx、数据清单.csv、接口示例.json；不得包含路径。") String fileName,
            @ToolParam(required = true, description = "输出格式：docx、xlsx、pptx、pdf、txt、md、html、csv、json 或 xml。") String format,
            @ToolParam(required = true, description = "完整文件内容。xlsx/csv 使用 CSV；pptx 用 --- 分隔页面；JSON/XML 必须是有效文本。") String content,
            @ToolParam(required = false, description = "可选交付摘要，例如 7 月项目数据清单；为空时由文件内容生成简短摘要。") String summary
    ) {
        trace.toolCall("produce_file", "format=" + safe(format) + ", fileNameLength=" + safe(fileName).length());
        try {
            String normalizedFormat = LocalDocumentAssetStore.normalizeFormat(format);
            String normalizedFileName = validateFileName(fileName, normalizedFormat);
            byte[] bytes = renderer.render(normalizedFormat, content);
            StoredDocument stored = store.create(artifacts.userId(), normalizedFileName, normalizedFormat, bytes,
                    summary(summary, content));
            artifacts.add(AiArtifact.document(store.readBytes(stored), stored.fileName(),
                    describe(stored), stored.assetId(), stored.version()));
            String result = describe(stored);
            trace.toolResult("produce_file", result);
            return result;
        } catch (RuntimeException exception) {
            String result = "文件生成失败：" + safeMessage(exception);
            trace.toolResult("produce_file", result);
            return result;
        }
    }

    private static String validateFileName(String fileName, String format) {
        String value = safe(fileName);
        if (value.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (value.contains("/") || value.contains("\\") || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("文件名不能包含路径或控制字符");
        }
        int dot = value.lastIndexOf('.');
        if (dot > 0 && dot < value.length() - 1) {
            String extension = LocalDocumentAssetStore.normalizeFormat(value.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (!format.equals(extension)) {
                throw new IllegalArgumentException("文件名扩展名与输出格式不一致");
            }
        }
        return value;
    }

    private static String describe(StoredDocument document) {
        return "文件已生成并会作为附件发送给用户：文件 " + document.fileName()
                + "，文档编号 " + document.assetId() + "，版本 v" + document.version()
                + "，格式 " + document.format();
    }

    private static String summary(String summary, String content) {
        String value = safe(summary);
        if (!value.isBlank()) {
            return value.length() <= 160 ? value : value.substring(0, 160);
        }
        String compact = safe(content).replaceAll("\\s+", " ");
        return compact.length() <= 100 ? "文件生产：" + compact : "文件生产：" + compact.substring(0, 100);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "请检查文件名、格式和内容后重试" : message;
    }
}
