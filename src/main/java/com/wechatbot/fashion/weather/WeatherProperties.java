package com.wechatbot.fashion.weather;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 天气 provider 切换配置。 */
@ConfigurationProperties(prefix = "app.weather")
public class WeatherProperties {
    /**
     * 天气 provider：
     * {@code uapis}（默认，uapis.cn 公共 API）或 {@code mcp}（外部天气 MCP Server）。
     */
    private String provider = "uapis";
    private final Mcp mcp = new Mcp();

    public String getProvider() { return provider; }
    public void setProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            this.provider = provider;
        }
    }

    public Mcp getMcp() { return mcp; }

    /** MCP 模式详情，绑定 {@code app.weather.mcp.*}。 */
    public static class Mcp {
        /** 外部天气 MCP Server 提供的工具名。 */
        private String toolName = "get_weather";

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) {
            if (toolName != null && !toolName.isBlank()) {
                this.toolName = toolName;
            }
        }
    }
}
