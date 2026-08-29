package com.wechatbot.fashion.bot.service;

import com.wechatbot.fashion.bot.config.ILinkRateLimitProperties;
import com.wechatbot.fashion.persistence.RedisOperationalStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ILinkMessageRateLimiterTest {

    @Test
    void limitsEachUserAndMessageTypeIndependently() {
        ILinkRateLimitProperties properties = new ILinkRateLimitProperties();
        properties.setLimits(Map.of("text", 2, "video", 1));
        ILinkMessageRateLimiter limiter = new ILinkMessageRateLimiter(properties);

        assertThat(limiter.tryAcquire("user-a", "text")).isTrue();
        assertThat(limiter.tryAcquire("user-a", "text")).isTrue();
        assertThat(limiter.tryAcquire("user-a", "text")).isFalse();
        assertThat(limiter.tryAcquire("user-b", "text")).isTrue();
        assertThat(limiter.tryAcquire("user-a", "video")).isTrue();
        assertThat(limiter.tryAcquire("user-a", "video")).isFalse();
    }

    @Test
    void permitsAgainAfterWindowAndCanBeDisabled() throws Exception {
        ILinkRateLimitProperties properties = new ILinkRateLimitProperties();
        properties.setWindow(Duration.ofMillis(5));
        properties.setLimits(Map.of("text", 1));
        ILinkMessageRateLimiter limiter = new ILinkMessageRateLimiter(properties);

        assertThat(limiter.tryAcquire("user", "text")).isTrue();
        assertThat(limiter.tryAcquire("user", "text")).isFalse();
        Thread.sleep(10L);
        assertThat(limiter.tryAcquire("user", "text")).isTrue();

        properties.setEnabled(false);
        assertThat(limiter.tryAcquire("user", "text")).isTrue();
        assertThat(limiter.tryAcquire("user", "text")).isTrue();
    }

    @Test
    void usesRedisResultWhenItIsAvailable() {
        ILinkRateLimitProperties properties = new ILinkRateLimitProperties();
        RedisOperationalStore redis = mock(RedisOperationalStore.class);
        when(redis.tryAcquire(anyString(), anyString(), any(Duration.class), anyInt()))
                .thenReturn(RedisOperationalStore.Result.REJECTED);
        ILinkMessageRateLimiter limiter = new ILinkMessageRateLimiter(properties, redis);

        assertThat(limiter.tryAcquire("user", "text")).isFalse();
    }
}
