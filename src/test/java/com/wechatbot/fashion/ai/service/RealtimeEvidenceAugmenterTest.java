package com.wechatbot.fashion.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechatbot.fashion.ai.config.RealtimeEvidenceProperties;
import com.wechatbot.fashion.ai.tool.WebSearchTools;
import com.wechatbot.fashion.persistence.RedisOperationalStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RealtimeEvidenceAugmenterTest {

    @Test
    void returnsSpecializedAndWebEvidenceAfterRunningThemInParallel() throws Exception {
        RealtimeEvidenceProperties properties = properties(Duration.ofSeconds(2));
        WebSearchTools webSearchTools = mock(WebSearchTools.class);
        AtomicBoolean webStarted = new AtomicBoolean();
        when(webSearchTools.webSearch(anyString())).thenAnswer(ignored -> {
            webStarted.set(true);
            return "联网补充：市场新闻";
        });

        try (Fixture fixture = fixture(properties, webSearchTools)) {
            String result = fixture.augmenter().augment(
                    "convert_currency",
                    "{\"base\":\"USD\",\"target\":\"CNY\"}",
                    () -> {
                        await(webStarted);
                        return "专用汇率：USD/CNY 7.2";
                    }
            );

            assertThat(result).contains("专用实时工具结果：convert_currency")
                    .contains("专用汇率：USD/CNY 7.2")
                    .contains("联网辅助检索")
                    .contains("联网补充：市场新闻");
            verify(webSearchTools).webSearch("实时查询 convert_currency USD CNY");
        }
    }

    @Test
    void preservesWebEvidenceWhenSpecializedToolFails() {
        WebSearchTools webSearchTools = mock(WebSearchTools.class);
        when(webSearchTools.webSearch(anyString())).thenReturn("联网补充：可用来源");

        try (Fixture fixture = fixture(properties(Duration.ofSeconds(2)), webSearchTools)) {
            String result = fixture.augmenter().augment(
                    "convert_currency",
                    "{\"base\":\"USD\",\"target\":\"CNY\"}",
                    () -> { throw new IllegalStateException("upstream down"); }
            );

            assertThat(result).contains("专用实时工具执行失败：IllegalStateException")
                    .contains("联网补充：可用来源");
        }
    }

    @Test
    void bypassesNonEligibleToolsWithoutStartingWebSearch() {
        WebSearchTools webSearchTools = mock(WebSearchTools.class);

        try (Fixture fixture = fixture(properties(Duration.ofSeconds(2)), webSearchTools)) {
            String result = fixture.augmenter().augment(
                    "resend_asset",
                    "{\"assetId\":\"img_1\"}",
                    () -> "图片已重新发送"
            );

            assertThat(result).isEqualTo("图片已重新发送");
            verify(webSearchTools, times(0)).webSearch(anyString());
        }
    }

    @Test
    void usesCachedWebEvidenceForRepeatedRealtimeQuery() {
        WebSearchTools webSearchTools = mock(WebSearchTools.class);
        when(webSearchTools.webSearch(anyString())).thenReturn("联网补充：缓存结果");

        try (Fixture fixture = fixture(properties(Duration.ofSeconds(2)), webSearchTools)) {
            fixture.augmenter().augment("convert_currency", "{\"base\":\"USD\",\"target\":\"CNY\"}", () -> "专用结果一");
            fixture.augmenter().augment("convert_currency", "{\"base\":\"USD\",\"target\":\"CNY\"}", () -> "专用结果二");

            verify(webSearchTools).webSearch("实时查询 convert_currency USD CNY");
        }
    }

    @Test
    void usesSharedRedisEvidenceBeforeRunningAnotherWebSearch() {
        WebSearchTools webSearchTools = mock(WebSearchTools.class);
        RedisOperationalStore redis = mock(RedisOperationalStore.class);
        when(redis.get("realtime-web", "实时查询 convert_currency USD CNY")).thenReturn("Redis 缓存结果");
        RealtimeEvidenceProperties properties = properties(Duration.ofSeconds(2));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        RealtimeEvidenceAugmenter augmenter = new RealtimeEvidenceAugmenter(
                properties, webSearchTools, new ObjectMapper(), executor, redis);
        try {
            String result = augmenter.augment("convert_currency", "{\"base\":\"USD\",\"target\":\"CNY\"}",
                    () -> "专用汇率：USD/CNY 7.2");

            assertThat(result).contains("Redis 缓存结果");
            verify(webSearchTools, times(0)).webSearch(anyString());
        } finally {
            augmenter.shutdown();
            executor.shutdownNow();
        }
    }

    @Test
    void returnsTimeoutEvidenceAfterTheSharedDeadline() {
        WebSearchTools webSearchTools = mock(WebSearchTools.class);
        when(webSearchTools.webSearch(anyString())).thenReturn("联网补充：及时返回");

        try (Fixture fixture = fixture(properties(Duration.ofMillis(200)), webSearchTools)) {
            String result = fixture.augmenter().augment("convert_currency", "{\"base\":\"USD\",\"target\":\"CNY\"}", () -> {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "过期的专用结果";
            });

            assertThat(result).contains("专用实时工具超时，已到达统一等待上限")
                    .contains("联网补充：及时返回");
        }
    }

    @Test
    void truncatesOversizedEvidenceBeforeItReturnsToTheModel() {
        RealtimeEvidenceProperties properties = properties(Duration.ofSeconds(2));
        properties.setMaxSpecializedResultCharacters(20);
        properties.setMaxWebResultCharacters(20);
        properties.setMaxCombinedResultCharacters(80);
        WebSearchTools webSearchTools = mock(WebSearchTools.class);
        when(webSearchTools.webSearch(anyString())).thenReturn("联网补充内容".repeat(200));

        try (Fixture fixture = fixture(properties, webSearchTools)) {
            String result = fixture.augmenter().augment(
                    "convert_currency", "{\"base\":\"USD\",\"target\":\"CNY\"}",
                    () -> "专用数据".repeat(200));

            assertThat(result).contains("工具结果已截断");
            assertThat(result.codePointCount(0, result.length())).isLessThanOrEqualTo(450);
        }
    }

    private static RealtimeEvidenceProperties properties(Duration timeout) {
        RealtimeEvidenceProperties properties = new RealtimeEvidenceProperties();
        properties.setTimeout(timeout);
        properties.setEligibleTools(Set.of("convert_currency"));
        return properties;
    }

    private static Fixture fixture(RealtimeEvidenceProperties properties, WebSearchTools webSearchTools) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        return new Fixture(executor, new RealtimeEvidenceAugmenter(
                properties, webSearchTools, new ObjectMapper(), executor
        ));
    }

    private static void await(AtomicBoolean condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (!condition.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(condition).isTrue();
    }

    private record Fixture(ExecutorService executor, RealtimeEvidenceAugmenter augmenter) implements AutoCloseable {
        @Override
        public void close() {
            augmenter.shutdown();
            executor.shutdownNow();
        }
    }
}
