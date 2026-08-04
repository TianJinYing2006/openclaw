package com.example.ykdsummer.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地联调日志开关。只允许输出截断后的文字摘要，禁止输出密钥、Base64 和完整文件内容。
 */
@ConfigurationProperties(prefix = "app.ai.trace")
public class AiTraceProperties {

    private boolean enabled = true;
    private int maxTextLength = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = Math.max(100, Math.min(maxTextLength, 5000));
    }
}
