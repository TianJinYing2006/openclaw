package com.example.ykdsummer.fashion.wardrobe.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 服装抠图 provider 切换配置。 */
@ConfigurationProperties(prefix = "app.fashion.cutout")
public class FashionCutoutProperties {
    /**
     * 抠图 provider：
     * {@code reference-image}（默认，通用图片编辑端点）或 {@code mcp}（外部抠图 MCP Server）。
     */
    private String provider = "reference-image";
    /** 抠图调用超时上限。 */
    private Duration providerTimeout = Duration.ofSeconds(120);
    private final Mcp mcp = new Mcp();

    public String getProvider() { return provider; }
    public void setProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            this.provider = provider;
        }
    }

    public Duration getProviderTimeout() { return providerTimeout; }
    public void setProviderTimeout(Duration providerTimeout) {
        if (providerTimeout != null && !providerTimeout.isNegative() && !providerTimeout.isZero()) {
            this.providerTimeout = providerTimeout;
        }
    }

    public Mcp getMcp() { return mcp; }

    /** MCP 模式详情，绑定 {@code app.fashion.cutout.mcp.*}。 */
    public static class Mcp {
        /** 外部抠图 MCP Server 提供的工具名。首次抠图工具。 */
        private String cutoutToolName = "garment_cutout";
        /** 外部抠图 MCP Server 提供的工具名。草稿修订工具；为空时回退到 cutoutToolName。 */
        private String reviseToolName = "garment_revise";
        /** MCP 调用超时；{@code null} 时复用 {@link FashionCutoutProperties#providerTimeout}。 */
        private Duration timeout;

        public String getCutoutToolName() { return cutoutToolName; }
        public void setCutoutToolName(String cutoutToolName) {
            if (cutoutToolName != null) {
                this.cutoutToolName = cutoutToolName;
            }
        }

        public String getReviseToolName() { return reviseToolName; }
        public void setReviseToolName(String reviseToolName) {
            if (reviseToolName != null) {
                this.reviseToolName = reviseToolName;
            }
        }

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) {
            if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
                this.timeout = timeout;
            }
        }
    }
}
