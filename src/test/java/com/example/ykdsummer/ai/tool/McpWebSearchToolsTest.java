package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class McpWebSearchToolsTest {

    private final WebSearchProperties properties = new WebSearchProperties();
    private final SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
    private final McpWebSearchTools service = new McpWebSearchTools(provider, properties);

    @Test
    void failsWhenTheConfiguredToolIsNotExposed() {
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        String result = service.search("今日科技新闻");

        assertThat(result).isEqualTo("搜索服务未配置: web_search");
    }

    @Test
    void formatsResultsWhenTheServerReturnsResults() {
        ToolCallback tool = tool("web_search", """
                {"results":[
                  {"title":"科技新闻","url":"https://example.com/news","snippet":"今日更新"},
                  {"title":"AI 进展","url":"https://example.com/ai","snippet":"最新模型"}
                ]}""");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        String result = service.search("今日科技新闻");

        assertThat(result).startsWith("搜索结果：");
        assertThat(result).contains("1. 科技新闻", "来源：https://example.com/news", "摘要：今日更新",
                "2. AI 进展");
    }

    @Test
    void reportsEmptyResults() {
        ToolCallback tool = tool("web_search", "{\"results\":[]}");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        String result = service.search("今日科技新闻");

        assertThat(result).isEqualTo("搜索没有可用结果");
    }

    @Test
    void surfacesServerError() {
        ToolCallback tool = tool("web_search", "{\"error\":\"rate limited\"}");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        String result = service.search("今日科技新闻");

        assertThat(result).isEqualTo("搜索失败：rate limited");
    }

    @Test
    void failsGracefullyWhenTheCallThrows() {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("web_search");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("timeout"));
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        String result = service.search("今日科技新闻");

        assertThat(result).isEqualTo("搜索失败：请稍后重试");
    }

    private static ToolCallback tool(String name, String response) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenReturn(response);
        return tool;
    }
}
