package com.wechatbot.fashion.bot.message;

import com.wechatbot.fashion.persistence.RedisOperationalStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Idempotency guard for iLink callbacks.
 *
 * <p>A Redis SET NX marker survives a Java restart and is scoped to an iLink bot instance. The bounded
 * local window is still used first to avoid a Redis round trip for an immediate duplicate and becomes the
 * automatic fallback whenever Redis is disabled or temporarily unavailable.</p>
 */
@Component
public class ILinkMessageDeduplicator {
    private static final int MAX_MESSAGES_PER_SCOPE = 1_000;
    private final RedisOperationalStore redis;
    private final Cache<String, RecentMessageIds> localWindows = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    public ILinkMessageDeduplicator(RedisOperationalStore redis) {
        this.redis = redis;
    }

    /** Returns true only for the first accepted delivery of an iLink message ID. */
    public boolean claim(String instanceScope, Long messageId) {
        if (messageId == null) return true;
        String scope = normalizeScope(instanceScope);
        RecentMessageIds local = localWindows.get(scope, ignored -> new RecentMessageIds(MAX_MESSAGES_PER_SCOPE));
        if (!local.claim(messageId)) return false;

        RedisOperationalStore.Result result = redis.claim(
                "message-dedup", scope + '\u0000' + messageId, redis.messageDedupTtl());
        return result != RedisOperationalStore.Result.REJECTED;
    }

    private static String normalizeScope(String value) {
        String safe = value == null ? "" : value.replace('\u0000', ' ').strip();
        return safe.isBlank() ? "legacy" : safe;
    }
}
