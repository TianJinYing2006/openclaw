package com.wechatbot.fashion.ai.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网页搜索 provider 切换配置。 */
@ConfigurationProperties(prefix = "app.web-search")
public class WebSearchProperties {
    /** 搜索 provider：{@code bocha}（默认）或 {@code mcp}。 */
    private String provider = "bocha";
    /** MCP 模式下调用的工具名。 */
    private String toolName = "web_search";
    /** MCP 搜索结果最大条数。 */
    private int maxResults = 8;

    public String getProvider() { return provider; }
    public void setProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            this.provider = provider;
        }
    }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            this.toolName = toolName;
        }
    }

    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) {
        if (maxResults > 0) {
            this.maxResults = maxResults;
        }
    }
}
