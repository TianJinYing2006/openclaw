package com.example.ykdsummer.ai.tool.feishu;

import com.example.ykdsummer.ai.tool.feishu.FeishuClient.FeishuApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 飞书文档工具集。支持搜索、读取、创建和追加云文档内容。
 */
@Component
@ConditionalOnProperty(prefix = "app.feishu.tools", name = "enabled", havingValue = "true")
public class FeishuDocTools {

    private static final Logger log = LoggerFactory.getLogger(FeishuDocTools.class);

    private final FeishuClient feishuClient;
    private final DocConverter docConverter;

    public FeishuDocTools(FeishuClient feishuClient, DocConverter docConverter) {
        this.feishuClient = feishuClient;
        this.docConverter = docConverter;
    }

    @Tool(
            name = "feishu_doc_search",
            description = "搜索飞书云文档，按关键词检索文档标题和内容，返回匹配的文档列表"
    )
    public String searchDoc(
            @ToolParam(required = true, description = "搜索关键词") String keyword
    ) {
        if (!feishuClient.isAvailable()) {
            return "飞书未配置，无法搜索文档";
        }
        try {
            log.info("Feishu search: keyword={}", keyword);
            String result = feishuClient.searchDocuments(keyword);
            return docConverter.formatSearchResults(result);
        } catch (FeishuApiException e) {
            log.warn("Feishu search failed: {}", e.getMessage());
            return "搜索文档失败：" + e.getMessage();
        }
    }

    @Tool(
            name = "feishu_doc_read",
            description = "读取飞书云文档的完整内容，根据文档 ID 或链接返回 Markdown 文本"
    )
    public String readDoc(
            @ToolParam(required = true, description = "飞书文档 ID（document_id）") String documentId
    ) {
        if (!feishuClient.isAvailable()) {
            return "飞书未配置，无法读取文档";
        }
        try {
            log.info("Feishu read: documentId={}", documentId);
            String raw = feishuClient.getDocumentRawContent(documentId);
            String url = feishuClient.getDocumentUrl(documentId);
            String result = docConverter.fromRawContent(raw);
            if (url != null) {
                return "文档链接: " + url + "\n\n" + result;
            }
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu read failed: {}", e.getMessage());
            return "读取文档失败：" + e.getMessage();
        }
    }

    @Tool(
            name = "feishu_doc_create",
            description = "在飞书创建一篇新的云文档，支持指定标题和 Markdown 内容。返回文档 ID 和链接"
    )
    public String createDoc(
            @ToolParam(required = true, description = "文档标题") String title,
            @ToolParam(required = false, description = "文档内容（Markdown 格式，可选）") String content
    ) {
        if (!feishuClient.isAvailable()) {
            return "飞书未配置，无法创建文档";
        }
        try {
            log.info("Feishu create: title={}", title);
            String docId = feishuClient.createDocument(title);

            if (content != null && !content.isBlank()) {
                String prepared = docConverter.prepareForWrite(content);
                feishuClient.convertAndAddContent(docId, prepared);
            }

            // 设为组织内可阅读（全员可通过链接访问）
            feishuClient.setDocumentPublicToTenant(docId);

            String url = feishuClient.getDocumentUrl(docId);
            if (url != null) {
                return "文档已创建，ID: " + docId + "\n链接: " + url;
            }
            return "文档已创建，ID: " + docId;
        } catch (FeishuApiException e) {
            log.warn("Feishu create failed: {}", e.getMessage());
            return "创建文档失败：" + e.getMessage();
        }
    }

    @Tool(
            name = "feishu_doc_append",
            description = "向已有的飞书云文档追加 Markdown 内容"
    )
    public String appendDoc(
            @ToolParam(required = true, description = "飞书文档 ID") String documentId,
            @ToolParam(required = true, description = "要追加的 Markdown 内容") String content
    ) {
        if (!feishuClient.isAvailable()) {
            return "飞书未配置，无法追加文档内容";
        }
        if (content == null || content.isBlank()) {
            return "追加内容为空，未做任何修改";
        }
        try {
            log.info("Feishu append: documentId={}", documentId);
            String prepared = docConverter.prepareForWrite(content);
            feishuClient.convertAndAddContent(documentId, prepared);
            String url = feishuClient.getDocumentUrl(documentId);
            if (url != null) {
                return "内容已追加到文档 " + documentId + "\n链接: " + url;
            }
            return "内容已追加到文档 " + documentId;
        } catch (FeishuApiException e) {
            log.warn("Feishu append failed: {}", e.getMessage());
            return "追加内容失败：" + e.getMessage();
        }
    }

    @Tool(
            name = "feishu_doc_update",
            description = "替换飞书云文档的全部内容（清空后重写）。"
                    + "⚠️ 注意：此操作会清空文档的所有现有内容（图片、表格等），文档的历史版本也会被清除。"
                    + "调用前必须向用户说明此风险，获得用户明确确认后再执行。"
    )
    public String updateDoc(
            @ToolParam(required = true, description = "飞书文档 ID") String documentId,
            @ToolParam(required = true, description = "新的 Markdown 内容（会替换文档全部现有内容）") String content
    ) {
        if (!feishuClient.isAvailable()) {
            return "飞书未配置，无法更新文档内容";
        }
        if (content == null || content.isBlank()) {
            return "内容为空，未做任何修改";
        }
        try {
            log.info("Feishu update: documentId={}", documentId);
            String prepared = docConverter.prepareForWrite(content);
            feishuClient.replaceDocumentContent(documentId, prepared);
            String url = feishuClient.getDocumentUrl(documentId);
            String result = "【⚠️ 注意】文档内容已全部替换！原内容（含图片、表格等）已被清空，历史版本也被清除。"
                    + "\n文档 " + documentId + " 已更新为新内容。";
            if (url != null) {
                result += "\n链接: " + url;
            }
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu update failed: {}", e.getMessage());
            return "更新文档失败：" + e.getMessage();
        }
    }
}
