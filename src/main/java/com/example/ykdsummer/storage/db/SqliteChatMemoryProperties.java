package com.example.ykdsummer.storage.db;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

/**
 * SQLite 数据库配置。
 * <p>默认数据库文件位于 {@code .db/wechat_bot.db}（项目根目录下）。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.db")
public class SqliteChatMemoryProperties {

    /** 数据库文件路径，默认 .db/wechat_bot.db */
    private Path path = Path.of(".db", "wechat_bot.db");

    /** 超过此时间未活跃的会话会被清理，默认 30 天 */
    private Duration cleanupMaxAge = Duration.ofDays(30);

    /** 清理任务执行间隔（毫秒），默认每天一次（86400000 ms） */
    private long cleanupIntervalMs = 86400000L;

    /** 每个会话保留的最大消息条数，默认 20 */
    private int defaultWindowSize = 20;

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path != null ? path : this.path;
    }

    public Duration getCleanupMaxAge() {
        return cleanupMaxAge;
    }

    public void setCleanupMaxAge(Duration cleanupMaxAge) {
        this.cleanupMaxAge = cleanupMaxAge != null && !cleanupMaxAge.isNegative() ? cleanupMaxAge : Duration.ofDays(30);
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs > 0 ? cleanupIntervalMs : 86400000L;
    }

    public int getDefaultWindowSize() {
        return defaultWindowSize;
    }

    public void setDefaultWindowSize(int defaultWindowSize) {
        this.defaultWindowSize = Math.max(2, defaultWindowSize);
    }
}
