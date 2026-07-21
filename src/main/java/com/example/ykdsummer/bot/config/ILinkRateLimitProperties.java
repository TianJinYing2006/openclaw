package com.example.ykdsummer.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** iLink 入站消息的按用户、按类型限流配置。 */
@Component
@ConfigurationProperties(prefix = "ilink.rate-limit")
public class ILinkRateLimitProperties {

    private static final Duration MAX_WINDOW = Duration.ofDays(1);
    private static final long MAX_USERS = 1_000_000L;

    private boolean enabled = true;
    private Duration window = Duration.ofSeconds(30);
    private long maxUsers = 10_000L;
    private Map<String, Integer> limits = defaultLimits();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        Duration positive = window == null || window.isZero() || window.isNegative()
                ? Duration.ofSeconds(30)
                : window;
        this.window = positive.compareTo(MAX_WINDOW) > 0 ? MAX_WINDOW : positive;
    }

    public long getMaxUsers() {
        return maxUsers;
    }

    public void setMaxUsers(long maxUsers) {
        this.maxUsers = Math.min(MAX_USERS, Math.max(1L, maxUsers));
    }

    public Map<String, Integer> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Integer> limits) {
        Map<String, Integer> merged = defaultLimits();
        if (limits != null) {
            limits.forEach((key, value) -> {
                if (key != null && value != null) {
                    merged.put(key.toLowerCase(Locale.ROOT), Math.max(1, value));
                }
            });
        }
        this.limits = merged;
    }

    public int limitFor(String messageType) {
        String safeType = messageType == null ? "default" : messageType.toLowerCase(Locale.ROOT);
        return Math.max(1, limits.getOrDefault(safeType, limits.getOrDefault("default", 10)));
    }

    private static Map<String, Integer> defaultLimits() {
        Map<String, Integer> values = new HashMap<>();
        values.put("text", 20);
        values.put("voice", 10);
        values.put("image", 5);
        values.put("file", 5);
        values.put("video", 2);
        values.put("default", 10);
        return values;
    }
}
