package com.example.ykdsummer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Explicit local opt-in: verifies the atomic operations against a real Redis service. */
@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class RedisOperationalStoreLiveTest {
    private LettuceConnectionFactory connectionFactory;
    private RedisOperationalStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("PERSISTENCE_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("PERSISTENCE_REDIS_PORT", "6379")));
        String password = System.getenv("PERSISTENCE_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) configuration.setPassword(password);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();

        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(template);
        ObjectProvider<PersistenceProperties> propertiesProvider = mock(ObjectProvider.class);
        when(propertiesProvider.getIfAvailable()).thenReturn(new PersistenceProperties());
        store = new RedisOperationalStore(redisProvider, propertiesProvider);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void claimsLimitsAndCachesThroughLocalRedis() {
        String identity = "redis-live-" + UUID.randomUUID();

        assertThat(store.claim("test-dedup", identity, Duration.ofSeconds(10)))
                .isEqualTo(RedisOperationalStore.Result.ACCEPTED);
        assertThat(store.claim("test-dedup", identity, Duration.ofSeconds(10)))
                .isEqualTo(RedisOperationalStore.Result.REJECTED);

        assertThat(store.tryAcquire("test-rate", identity, Duration.ofSeconds(10), 1))
                .isEqualTo(RedisOperationalStore.Result.ACCEPTED);
        assertThat(store.tryAcquire("test-rate", identity, Duration.ofSeconds(10), 1))
                .isEqualTo(RedisOperationalStore.Result.REJECTED);

        store.put("test-cache", identity, "cached-value", Duration.ofSeconds(10));
        assertThat(store.get("test-cache", identity)).isEqualTo("cached-value");
    }
}
