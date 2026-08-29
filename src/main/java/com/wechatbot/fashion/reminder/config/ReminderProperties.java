package com.wechatbot.fashion.reminder.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime limits for durable WeChat reminder delivery. */
@ConfigurationProperties(prefix = "app.reminder")
public class ReminderProperties {
    private boolean enabled = true;
    private String zoneId = "Asia/Shanghai";
    private Duration pollInterval = Duration.ofSeconds(15);
    private Duration processingLease = Duration.ofMinutes(15);
    private Duration contextRetryInterval = Duration.ofMinutes(1);
    private int batchSize = 20;
    private int workerThreads = 4;
    private int queueCapacity = 100;
    private int maxDeliveryAttempts = 3;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }
    public Duration getPollInterval() { return positive(pollInterval, Duration.ofSeconds(15)); }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getProcessingLease() { return positive(processingLease, Duration.ofMinutes(15)); }
    public void setProcessingLease(Duration processingLease) { this.processingLease = processingLease; }
    public Duration getContextRetryInterval() { return positive(contextRetryInterval, Duration.ofMinutes(1)); }
    public void setContextRetryInterval(Duration contextRetryInterval) { this.contextRetryInterval = contextRetryInterval; }
    public int getBatchSize() { return bounded(batchSize, 1, 100); }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getWorkerThreads() { return bounded(workerThreads, 1, 16); }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    public int getQueueCapacity() { return bounded(queueCapacity, 1, 1000); }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getMaxDeliveryAttempts() { return bounded(maxDeliveryAttempts, 1, 10); }
    public void setMaxDeliveryAttempts(int maxDeliveryAttempts) { this.maxDeliveryAttempts = maxDeliveryAttempts; }

    public ZoneId zone() {
        try {
            return ZoneId.of(zoneId == null || zoneId.isBlank() ? "Asia/Shanghai" : zoneId.strip());
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
