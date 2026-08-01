package com.example.ykdsummer.bot.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.persistence.RedisOperationalStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ILinkMessageDeduplicatorTest {

    @Test
    void claimsOnlyOnceLocallyBeforeConsultingRedisAgain() {
        RedisOperationalStore redis = mock(RedisOperationalStore.class);
        when(redis.messageDedupTtl()).thenReturn(Duration.ofHours(1));
        when(redis.claim(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisOperationalStore.Result.ACCEPTED);
        ILinkMessageDeduplicator deduplicator = new ILinkMessageDeduplicator(redis);

        assertThat(deduplicator.claim("managed:instance-a", 42L)).isTrue();
        assertThat(deduplicator.claim("managed:instance-a", 42L)).isFalse();

        verify(redis, times(1)).claim(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void honorsDuplicateClaimFromAnotherProcess() {
        RedisOperationalStore redis = mock(RedisOperationalStore.class);
        when(redis.messageDedupTtl()).thenReturn(Duration.ofHours(1));
        when(redis.claim(anyString(), anyString(), any(Duration.class)))
                .thenReturn(RedisOperationalStore.Result.REJECTED);

        assertThat(new ILinkMessageDeduplicator(redis).claim("managed:instance-b", 42L)).isFalse();
    }
}
