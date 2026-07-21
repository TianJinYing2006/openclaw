package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.bot.config.ILinkRateLimitProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * 防止同一用户在很短时间内连续提交大量外部 AI、视频或文件任务。
 * 每种消息类型独立计数，不保存消息正文。
 */
@Component
public class ILinkMessageRateLimiter {

    private final ILinkRateLimitProperties properties;
    private final Cache<String, SlidingWindow> windows;

    public ILinkMessageRateLimiter(ILinkRateLimitProperties properties) {
        this.properties = properties;
        Duration retention = properties.getWindow().multipliedBy(2);
        this.windows = Caffeine.newBuilder()
                .maximumSize(properties.getMaxUsers() * 6L)
                .expireAfterAccess(retention)
                .build();
    }

    public boolean tryAcquire(String userId, String messageType) {
        if (!properties.isEnabled()) {
            return true;
        }
        String safeUser = userId == null ? "unknown" : userId;
        String safeType = messageType == null ? "default" : messageType.toLowerCase(Locale.ROOT);
        SlidingWindow window = windows.get(safeUser + '\u0000' + safeType, ignored -> new SlidingWindow());
        return window.tryAcquire(
                System.nanoTime(),
                properties.getWindow().toNanos(),
                properties.limitFor(safeType)
        );
    }

    private static final class SlidingWindow {
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();

        private synchronized boolean tryAcquire(long now, long windowNanos, int limit) {
            long oldestAllowed = now - windowNanos;
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= oldestAllowed) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= limit) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}
