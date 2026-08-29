package com.wechatbot.fashion.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;

/** Fails fast when persistence explicitly requires Redis but the configured instance is unavailable. */
@Component
@ConditionalOnProperty(prefix = "app.persistence", name = {"enabled", "redis.enabled"}, havingValue = "true")
public class RedisConnectionVerifier implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RedisConnectionVerifier.class);
    private final StringRedisTemplate redis;

    public RedisConnectionVerifier(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        String reply = redis.execute((RedisCallback<String>) connection -> connection.ping());
        if (!"PONG".equalsIgnoreCase(reply)) {
            throw new IllegalStateException("Redis did not return PONG");
        }
        log.info("Redis persistence support connected");
    }
}
