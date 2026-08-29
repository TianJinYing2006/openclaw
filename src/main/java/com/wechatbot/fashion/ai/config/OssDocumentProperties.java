package com.wechatbot.fashion.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Documents reuse the private image bucket credentials but keep a separate object-key prefix. */
@ConfigurationProperties(prefix = "oss.document")
public class OssDocumentProperties {
    private boolean enabled;
    private String prefix = "ilink-bot/documents";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) {
        this.prefix = prefix == null || prefix.isBlank() ? "ilink-bot/documents" : prefix.strip();
    }
}
