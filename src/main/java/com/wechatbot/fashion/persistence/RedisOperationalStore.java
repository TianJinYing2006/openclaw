package com.wechatbot.fashion.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Short-lived Redis state only. Durable conversations, assets and tasks remain in MySQL.
 *
 * <p>The adapter deliberately treats a Redis outage as an optional-cache failure. Callers receive an
 * {@link Result#UNAVAILABLE} result and fall back to their bounded in-process implementation, so a
 * temporary local Redis restart does not stop WeChat message handling.</p>
 */
@Component
public class RedisOperationalStore {
    private static final Logger log = LoggerFactory.getLogger(RedisOperationalStore.class);
    private static final long FAILURE_LOG_INTERVAL_MILLIS = Duration.ofSeconds(30).toMillis();
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
            if redis.call('ZCARD', KEYS[1]) >= limit then
                redis.call('PEXPIRE', KEYS[1], window)
                return 0
            end
            redis.call('ZADD', KEYS[1], now, ARGV[4])
            redis.call('PEXPIRE', KEYS[1], window)
            return 1
            """, Long.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<PersistenceProperties> persistenceProperties;
    private final AtomicLong nextFailureLogAt = new AtomicLong();

    public RedisOperationalStore(ObjectProvider<StringRedisTemplate> redisProvider,
                                 ObjectProvider<PersistenceProperties> persistenceProperties) {
        this.redisProvider = redisProvider;
        this.persistenceProperties = persistenceProperties;
    }

    /** Atomically records an idempotency marker. */
    public Result claim(String namespace, String identity, Duration ttl) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return Result.UNAVAILABLE;
        try {
            Boolean created = redis.opsForValue().setIfAbsent(key(namespace, identity), "1", positiveTtl(ttl));
            return Boolean.TRUE.equals(created) ? Result.ACCEPTED : Result.REJECTED;
        } catch (RuntimeException exception) {
            unavailable("claim", exception);
            return Result.UNAVAILABLE;
        }
    }

    /** True sliding-window rate limiting, atomically evaluated by Redis. */
    public Result tryAcquire(String namespace, String identity, Duration window, int limit) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return Result.UNAVAILABLE;
        Duration safeWindow = positiveTtl(window);
        try {
            Long outcome = redis.execute(SLIDING_WINDOW_SCRIPT, List.of(key(namespace, identity)),
                    Long.toString(System.currentTimeMillis()), Long.toString(safeWindow.toMillis()),
                    Integer.toString(Math.max(1, limit)), UUID.randomUUID().toString());
            return Long.valueOf(1L).equals(outcome) ? Result.ACCEPTED : Result.REJECTED;
        } catch (RuntimeException exception) {
            unavailable("rate limit", exception);
            return Result.UNAVAILABLE;
        }
    }

    /** Returns {@code null} for both a cache miss and an unavailable optional Redis instance. */
    public String get(String namespace, String identity) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return null;
        try {
            return redis.opsForValue().get(key(namespace, identity));
        } catch (RuntimeException exception) {
            unavailable("read", exception);
            return null;
        }
    }

    public void put(String namespace, String identity, String value, Duration ttl) {
        if (value == null) return;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        try {
            redis.opsForValue().set(key(namespace, identity), value, positiveTtl(ttl));
        } catch (RuntimeException exception) {
            unavailable("write", exception);
        }
    }

    public Duration messageDedupTtl() {
        PersistenceProperties properties = persistenceProperties.getIfAvailable();
        return properties == null ? Duration.ofHours(24) : properties.getRedis().getMessageDedupTtl();
    }

    private String key(String namespace, String identity) {
        return prefix() + ':' + safeNamespace(namespace) + ':' + hash(identity);
    }

    private String prefix() {
        PersistenceProperties properties = persistenceProperties.getIfAvailable();
        String configured = properties == null ? "ykd" : properties.getRedis().getKeyPrefix();
        String cleaned = configured == null ? "" : configured.strip().replaceAll("[^A-Za-z0-9:_-]", "");
        return cleaned.isBlank() ? "ykd" : cleaned;
    }

    private static String safeNamespace(String value) {
        String cleaned = value == null ? "" : value.strip().replaceAll("[^A-Za-z0-9:_-]", "");
        return cleaned.isBlank() ? "state" : cleaned;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Duration positiveTtl(Duration value) {
        return value == null || value.isNegative() || value.isZero() ? Duration.ofSeconds(1) : value;
    }

    private void unavailable(String operation, RuntimeException exception) {
        long now = System.currentTimeMillis();
        long next = nextFailureLogAt.get();
        if (now >= next && nextFailureLogAt.compareAndSet(next, now + FAILURE_LOG_INTERVAL_MILLIS)) {
            log.warn("Redis operational {} unavailable; using bounded local fallback ({})", operation,
                    exception.getClass().getSimpleName());
        }
    }

    public enum Result {
        ACCEPTED,
        REJECTED,
        UNAVAILABLE
    }
}
