package com.example.ykdsummer.ai.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 专用实时查询的联网辅助策略。
 *
 * <p>只有白名单中的无副作用查询 Tool 可以并行补充联网证据。写入、审批、生成和资产操作
 * 必须继续在原线程同步执行，不能因为本策略被意外并发。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.ai.realtime-augmentation")
public class RealtimeEvidenceProperties {

    private boolean enabled = true;
    private Duration timeout = Duration.ofSeconds(15);
    private int workerThreads = 8;
    private int queueCapacity = 64;
    private int maxQueryCharacters = 320;
    private int maxSpecializedResultCharacters = 1_200;
    private int maxWebResultCharacters = 1_200;
    private int maxCombinedResultCharacters = 2_400;
    private Duration cacheTtl = Duration.ofSeconds(30);
    private long maxCacheEntries = 500;
    private Set<String> eligibleTools = Set.of();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) {
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(15) : timeout;
    }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = Math.max(1, Math.min(32, workerThreads)); }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = Math.max(1, Math.min(1000, queueCapacity)); }
    public int getMaxQueryCharacters() { return maxQueryCharacters; }
    public void setMaxQueryCharacters(int maxQueryCharacters) { this.maxQueryCharacters = Math.max(64, maxQueryCharacters); }
    public int getMaxSpecializedResultCharacters() { return maxSpecializedResultCharacters; }
    public void setMaxSpecializedResultCharacters(int value) { this.maxSpecializedResultCharacters = Math.max(200, value); }
    public int getMaxWebResultCharacters() { return maxWebResultCharacters; }
    public void setMaxWebResultCharacters(int value) { this.maxWebResultCharacters = Math.max(200, value); }
    public int getMaxCombinedResultCharacters() { return maxCombinedResultCharacters; }
    public void setMaxCombinedResultCharacters(int value) { this.maxCombinedResultCharacters = Math.max(400, value); }
    public Duration getCacheTtl() { return cacheTtl; }
    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()
                ? Duration.ofSeconds(30) : cacheTtl;
    }
    public long getMaxCacheEntries() { return maxCacheEntries; }
    public void setMaxCacheEntries(long maxCacheEntries) { this.maxCacheEntries = Math.max(1L, maxCacheEntries); }
    public Set<String> getEligibleTools() { return Set.copyOf(eligibleTools); }
    public void setEligibleTools(Set<String> eligibleTools) {
        this.eligibleTools = eligibleTools == null ? Set.of() : eligibleTools.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isEligible(String toolName) {
        return toolName != null && eligibleTools.contains(toolName.strip().toLowerCase(Locale.ROOT));
    }

}
