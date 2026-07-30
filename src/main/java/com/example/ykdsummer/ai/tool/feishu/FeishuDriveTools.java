package com.example.ykdsummer.ai.tool.feishu;

import com.example.ykdsummer.ai.tool.feishu.FeishuClient.FeishuApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 飞书云盘（Drive）文件搜索工具。
 *
 * <p>提供搜索云盘文件的能力，
 * 供 Spring AI Function Calling 自动调用。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.feishu.tools", name = "enabled", havingValue = "true")
public class FeishuDriveTools {

    private static final Logger log = LoggerFactory.getLogger(FeishuDriveTools.class);

    private final FeishuClient feishuClient;

    public FeishuDriveTools(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    @Tool(name = "feishu_drive_file_search", description = """
            搜索飞书云盘中的文件。
            按关键词搜索文件名和内容。
            适用于：查找云盘中的文档、表格、幻灯片、文件夹等。
            """)
    public String driveFileSearch(
            @ToolParam(required = true, description = "搜索关键词，如 '项目方案'、'季度汇报' 等") String keyword,
            @ToolParam(required = false, description = "每页返回的文件数，默认 20") Integer pageSize,
            @ToolParam(required = false, description = "分页标记，用于翻页") String pageToken
    ) {
        log.info("Feishu drive file search: keyword={}, pageSize={}", keyword, pageSize);
        try {
            String result = feishuClient.searchDriveFiles(keyword, pageSize, pageToken);
            log.info("Feishu drive file search success");
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu drive file search failed: {}", e.getMessage());
            return "【搜索失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("Feishu drive file search error", e);
            return "【搜索异常】" + e.getMessage();
        }
    }
}
