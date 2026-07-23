package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import com.example.ykdsummer.bot.document.DocumentRenderer;
import com.example.ykdsummer.bot.document.DocumentTextExtractor;
import com.example.ykdsummer.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档处理工具集。支持读取、生成、编辑和转换 Office 文档。
 */
@Component
public class DocumentTools {

    private static final Logger log = LoggerFactory.getLogger(DocumentTools.class);

    /** AI 响应后待发送的文档文件字节（单线程内传递）。 */
    private static final ThreadLocal<PendingDocument> pendingDocument = new ThreadLocal<>();

    private final DocumentTextExtractor textExtractor;
    private final DocumentRenderer renderer;
    private final FileStorageService fileStorage;

    public DocumentTools(
            DocumentTextExtractor textExtractor,
            DocumentRenderer renderer,
            FileStorageService fileStorage
    ) {
        this.textExtractor = textExtractor;
        this.renderer = renderer;
        this.fileStorage = fileStorage;
    }

    @Tool(name = "read_document", description = "读取 PDF、DOCX、XLSX、PPTX、TXT 等文件的内容，返回纯文本")
    public String readDocument(
            @ToolParam(description = "文件路径") String filePath
    ) {
        try {
            Path path = Path.of(filePath);
            String extension = getExtension(path.getFileName().toString());
            byte[] bytes = Files.readAllBytes(path);
            return textExtractor.extract(extension, bytes)
                    .orElse("无法提取文件内容：不支持的格式或文件已损坏");
        } catch (IOException exception) {
            log.warn("Cannot read document: {}", filePath, exception);
            return "无法读取文件：" + exception.getMessage();
        }
    }

    @Tool(name = "generate_document", description = "生成文档文件（pdf/docx/xlsx/pptx/md/txt/csv/json/xml/html）。仅当用户明确要求创建或生成文档文件时调用，不要主动为用户创建文档，不要将文档作为搜索替代方案。")
    public String generateDocument(
            @ToolParam(description = "文档内容文本") String content,
            @ToolParam(description = "目标格式：pdf、docx、xlsx、pptx、md、txt、csv、json、xml、html") String format
    ) {
        String normalizedFormat = format.toLowerCase().strip();
        try {
            byte[] result = renderer.render(normalizedFormat, content);
            if (result == null || result.length == 0) {
                return "生成文档失败：渲染结果为空";
            }
            String fileName = "generated_" + System.currentTimeMillis() + "." + normalizedFormat;
            Path outputPath = fileStorage.writeOutput(
                    AgentSessionContext.currentUserId(), AgentSessionContext.currentSessionId(), fileName, result);
            log.info("Document generated: {} ({} bytes)", outputPath, result.length);
            pendingDocument.set(new PendingDocument(result, fileName));
            return "文档已生成：" + outputPath.toAbsolutePath();
        } catch (RuntimeException exception) {
            log.warn("Document generation failed", exception);
            return "生成文档失败：" + exception.getMessage();
        }
    }

    @Tool(name = "edit_document", description = "修改已有的 Office 文档（DOCX/XLSX/PPTX），返回修改后的文件路径")
    public String editDocument(
            @ToolParam(description = "待修改的文件路径") String filePath,
            @ToolParam(description = "修改描述，例如：'把标题改为xxx'") String modificationDescription
    ) {
        try {
            Path path = Path.of(filePath);
            byte[] originalBytes = Files.readAllBytes(path);
            String extension = getExtension(path.getFileName().toString());

            // 构造替换全文的 JSON 操作指令
            String operationsJson = "{\"operations\": [{\"type\": \"replace_document\", \"content\": \""
                    + modificationDescription.replace("\"", "\\\"")
                    + "\"}]}";

            byte[] resultBytes = renderer.applyOperations(extension, originalBytes, operationsJson);
            if (resultBytes == null || resultBytes.length == 0) {
                return "修改文档失败：结果为空";
            }
            String fileName = "edited_" + System.currentTimeMillis() + "." + extension;
            Path outputPath = fileStorage.writeOutput(
                    AgentSessionContext.currentUserId(), AgentSessionContext.currentSessionId(), fileName, resultBytes);
            log.info("Document edited: {}", outputPath);
            return "文档已修改：" + outputPath.toAbsolutePath();
        } catch (IOException exception) {
            log.warn("Cannot edit document: {}", filePath, exception);
            return "修改文档失败：" + exception.getMessage();
        }
    }

    @Tool(name = "convert_document", description = "转换文档格式，例如 PDF 转 DOCX、DOCX 转 PDF 等，返回转换后的文件路径")
    public String convertDocument(
            @ToolParam(description = "源文件路径") String filePath,
            @ToolParam(description = "目标格式：pdf、docx、xlsx、pptx、md、txt、csv、json、xml、html") String targetFormat
    ) {
        try {
            Path path = Path.of(filePath);
            String sourceExtension = getExtension(path.getFileName().toString());
            byte[] sourceBytes = Files.readAllBytes(path);
            String normalizedFormat = targetFormat.toLowerCase().strip();

            // 读取源文件内容后重新渲染为目标格式
            String text = textExtractor.extract(sourceExtension, sourceBytes)
                    .orElse("");
            byte[] resultBytes = renderer.render(normalizedFormat, text);
            if (resultBytes == null || resultBytes.length == 0) {
                return "转换文档失败：结果为空";
            }
            String fileName = "converted_" + System.currentTimeMillis() + "." + normalizedFormat;
            Path outputPath = fileStorage.writeOutput(
                    AgentSessionContext.currentUserId(), AgentSessionContext.currentSessionId(), fileName, resultBytes);
            log.info("Document converted: {} -> {} ({} bytes)", sourceExtension, normalizedFormat, resultBytes.length);
            return "文档已转换：" + outputPath.toAbsolutePath();
        } catch (IOException exception) {
            log.warn("Cannot convert document: {}", filePath, exception);
            return "转换文档失败：" + exception.getMessage();
        }
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    /** 检查是否有待发送的文档文件。调用后自动清除。 */
    public static PendingDocument consumePendingDocument() {
        PendingDocument pd = pendingDocument.get();
        if (pd == null) return null;
        pendingDocument.remove();
        return pd;
    }

    public record PendingDocument(byte[] bytes, String fileName) {
        public PendingDocument { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
