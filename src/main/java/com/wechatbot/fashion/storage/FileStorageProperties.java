package com.wechatbot.fashion.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 本地工作区配置。根路径可配置，默认 {@code ${user.dir}/.agent-workspace/}。
 *
 * <p>目录结构：{root}/{userId}/{sessionId}/{input|temp|output}/</p>
 */
@ConfigurationProperties(prefix = "app.workspace")
public class FileStorageProperties {

    /** 本地工作区根目录。默认 .agent-workspace（位于 JVM 工作目录下）。 */
    private Path root = Path.of(System.getProperty("user.dir", "."), ".agent-workspace");

    /** 会话目录多久没有活动后可以被清理任务删除。 */
    private Duration cleanupAge = Duration.ofMinutes(5);

    /** 兜底清理任务的执行间隔。 */
    private Duration cleanupInterval = Duration.ofMinutes(1);

    /** 单个会话目录下的最大临时文件总大小（字节），防止磁盘写满。 */
    private long maxSessionBytes = 500 * 1024 * 1024L;

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = root != null ? root.normalize().toAbsolutePath() : this.root;
    }

    public Duration getCleanupAge() {
        return cleanupAge;
    }

    public void setCleanupAge(Duration cleanupAge) {
        this.cleanupAge = cleanupAge != null && !cleanupAge.isNegative() ? cleanupAge : Duration.ofMinutes(5);
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval != null && !cleanupInterval.isNegative() ? cleanupInterval : Duration.ofMinutes(1);
    }

    public long getMaxSessionBytes() {
        return maxSessionBytes;
    }

    public void setMaxSessionBytes(long maxSessionBytes) {
        this.maxSessionBytes = Math.max(1_048_576L, maxSessionBytes);
    }
}
