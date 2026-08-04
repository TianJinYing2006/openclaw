package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.mcp.McpToolSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MCP 搜索实现：通过外部搜索 MCP Server 的 {@code web_search} 工具检索实时网页信息。
 *
 * <p>返回格式与博查实现保持一致的成功前缀 "搜索结果："，供降级路径统一识别。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.web-search", name = "provider", havingValue = "mcp")
public class McpWebSearchTools implements WebSearchProvider {
    private static final Logger log = LoggerFactory.getLogger(McpWebSearchTools.class);

    private final SyncMcpToolCallbackProvider toolProvider;
    private final WebSearchProperties properties;

    public McpWebSearchTools(SyncMcpToolCallbackProvider toolProvider, WebSearchProperties properties) {
        this.toolProvider = toolProvider;
        this.properties = properties;
    }

    @Tool(name = "search_web", description = "搜索公开网页中的实时信息、新闻和热点。"
            + "只有问题需要最新外部事实时才调用；返回来源链接和摘要。")
    public String searchWeb(@ToolParam(required = true, description = "要搜索的关键词或完整问题") String query) {
        return search(query);
    }

    @Override
    public String search(String query) {
        String toolName = properties.getToolName();
        try {
            ToolCallback tool = McpToolSupport.findTool(toolProvider, toolName);
            if (tool == null) {
                log.warn("Web search MCP tool not found, tool={}", toolName);
                return "搜索服务未配置: " + toolName;
            }
            String jsonArgs = McpToolSupport.objectMapper().createObjectNode().put("query", McpToolSupport.safe(query)).toString();
            String raw = tool.call(jsonArgs);
            return formatResults(raw);
        } catch (Exception exception) {
            log.warn("Web search MCP call failed, tool={}, type={}", toolName,
                    exception.getClass().getSimpleName(), exception);
            return "搜索失败：请稍后重试";
        }
    }

    private String formatResults(String raw) throws JsonProcessingException {
        JsonNode node = McpToolSupport.objectMapper().readTree(McpToolSupport.extractJson(raw));
        if (node == null || !node.isObject()) {
            return "搜索没有可用结果";
        }
        JsonNode error = node.get("error");
        if (error != null && !error.asText("").isBlank()) {
            log.warn("Web search MCP server reported error: {}", error.asText());
            return "搜索失败：" + error.asText().strip();
        }
        JsonNode results = node.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return "搜索没有可用结果";
        }
        StringBuilder output = new StringBuilder("搜索结果：\n");
        int max = properties.getMaxResults();
        int index = 1;
        for (JsonNode item : results) {
            if (index > max) {
                break;
            }
            String title = item.path("title").asText("");
            String url = item.path("url").asText("");
            String snippet = item.path("snippet").asText("");
            if (title.isBlank() && url.isBlank()) {
                continue;
            }
            output.append(index++).append(". ").append(title).append('\n')
                    .append("来源：").append(url).append('\n')
                    .append("摘要：").append(snippet).append("\n\n");
        }
        return index == 1 ? "搜索没有可用结果" : output.toString().strip();
    }
}
