package com.example.ykdsummer.fashion.wardrobe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 服装识别 provider 切换配置。 */
@ConfigurationProperties(prefix = "app.fashion.analysis")
public class FashionAnalysisProperties {
    /**
     * 识别 provider：
     * {@code chat-completions}（默认，ChatCompletions 视觉模型）或 {@code mcp}（外部识别 MCP Server）。
     */
    private String provider = "chat-completions";
    private final Mcp mcp = new Mcp();

    public String getProvider() { return provider; }
    public void setProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            this.provider = provider;
        }
    }

    public Mcp getMcp() { return mcp; }

    /** MCP 模式详情，绑定 {@code app.fashion.analysis.mcp.*}。 */
    public static class Mcp {
        /** 外部识别 MCP Server 提供的工具名。 */
        private String toolName = "wardrobe_photo_analysis";
        /** 分析版本标识，持久化为候选 metadata。 */
        private String promptVersion = "mcp-v2";
        /** MCP 调用超时（秒）；0 或负数表示使用默认超时。 */
        private long timeoutSeconds = 60;

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) {
            if (toolName != null && !toolName.isBlank()) {
                this.toolName = toolName;
            }
        }

        public String getPromptVersion() { return promptVersion; }
        public void setPromptVersion(String promptVersion) {
            if (promptVersion != null && !promptVersion.isBlank()) {
                this.promptVersion = promptVersion;
            }
        }

        public long getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
