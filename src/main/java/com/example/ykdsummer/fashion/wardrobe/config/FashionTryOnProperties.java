package com.example.ykdsummer.fashion.wardrobe.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provider deadline for background try-on work. It is intentionally separate from normal image edit timeout. */
@ConfigurationProperties(prefix = "app.fashion.tryon")
public class FashionTryOnProperties {
    /** Try-on provider: {@code reference-image} (default) or {@code mcp}. */
    private String provider = "reference-image";
    private Duration providerTimeout = Duration.ofSeconds(150);
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

    /** MCP mode details. Nested {@code app.fashion.tryon.mcp.*} bind here. */
    public static class Mcp {
        /** External try-on MCP Server tool name. */
        private String toolName = "virtual_try_on";
        /** MCP call timeout; {@code null} reuses {@link #providerTimeout}. */
        private Duration timeout;

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) {
            if (toolName != null && !toolName.isBlank()) {
                this.toolName = toolName;
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
