package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.bot.config.ILinkRateLimitProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
