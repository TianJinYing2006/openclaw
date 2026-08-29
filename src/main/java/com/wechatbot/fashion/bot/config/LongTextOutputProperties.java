package com.wechatbot.fashion.bot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 微信长文本输出的交互式分流配置。 */
@ConfigurationProperties(prefix = "app.reply.long-text")
public class LongTextOutputProperties {
    private boolean enabled = true;
    private int thresholdCharacters = 1_800;
    private Duration pendingTtl = Duration.ofMinutes(10);
    private long maxPendingUsers = 10_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getThresholdCharacters() { return thresholdCharacters; }
    public void setThresholdCharacters(int value) { this.thresholdCharacters = Math.max(200, value); }
    public Duration getPendingTtl() { return pendingTtl; }
    public void setPendingTtl(Duration value) {
        this.pendingTtl = value == null || value.isNegative() || value.isZero() ? Duration.ofMinutes(10) : value;
    }
    public long getMaxPendingUsers() { return maxPendingUsers; }
    public void setMaxPendingUsers(long value) { this.maxPendingUsers = Math.max(1L, value); }
}
