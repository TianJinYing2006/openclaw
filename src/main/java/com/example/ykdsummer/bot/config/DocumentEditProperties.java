package com.example.ykdsummer.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

/** 文档模式的本地存储、超时和版本安全限制。 */
@Component
@ConfigurationProperties(prefix = "app.document")
public class DocumentEditProperties {

    private boolean enabled = true;
    private Path storageDirectory = Path.of(".documents");
    private Duration idleTimeout = Duration.ofHours(2);
    private int maxVersions = 20;
    private int maxOutputBytes = 20 * 1024 * 1024;
    /** 文件问答和操作提取不需要长推理，默认 low 可避开当前中转的 60 秒超时。 */
    private String reasoningEffort = "low";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getStorageDirectory() { return storageDirectory; }
    public void setStorageDirectory(Path storageDirectory) { this.storageDirectory = storageDirectory; }
    public Duration getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Duration idleTimeout) { this.idleTimeout = idleTimeout; }
    public int getMaxVersions() { return maxVersions; }
    public void setMaxVersions(int maxVersions) { this.maxVersions = Math.max(2, maxVersions); }
    public int getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(int maxOutputBytes) { this.maxOutputBytes = Math.max(1024, maxOutputBytes); }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? "low" : reasoningEffort.strip();
    }
}
