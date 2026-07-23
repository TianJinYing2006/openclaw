package com.example.ykdsummer.ai.tool.feishu;

import org.springframework.stereotype.Component;

/**
 * Markdown ↔ 飞书文档块结构转换器。
 *
 * <p>飞书 Convert API 负责将 Markdown 转换为飞书 Block 结构并插入文档。
 * 此工具类封装转换前后的文本处理逻辑。</p>
 */
@Component
public class DocConverter {

    /**
     * 准备写入文档的 Markdown 内容（清理/格式化）。
     *
     * @param rawMarkdown 原始 Markdown 文本
     * @return 清理后的 Markdown
     */
    public String prepareForWrite(String rawMarkdown) {
        if (rawMarkdown == null || rawMarkdown.isBlank()) {
            return "";
        }
        // 去除首尾空白，保留中间格式
        return rawMarkdown.strip();
    }

    /**
     * 从飞书返回的原始内容中提取可读的文本。
     *
     * @param rawContent 飞书 raw_content API 返回的内容（通常是 Markdown）
     * @return 处理后的纯文本/Markdown
     */
    public String fromRawContent(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "（文档内容为空）";
        }
        // raw_content API 已经返回 Markdown 格式的文本，直接使用
        return rawContent;
    }

    /**
     * 从搜索返回的 JSON 中提取文档摘要列表。
     *
     * @param searchResultJson 搜索结果的 JSON 字符串
     * @return 格式化的文档列表
     */
    public String formatSearchResults(String searchResultJson) {
        if (searchResultJson == null || searchResultJson.isBlank()) {
            return "未找到匹配的文档";
        }
        // 简化处理：直接返回原始 JSON 让 LLM 自行解析
        // 后续可根据实际返回格式做美化
        return searchResultJson;
    }
}
