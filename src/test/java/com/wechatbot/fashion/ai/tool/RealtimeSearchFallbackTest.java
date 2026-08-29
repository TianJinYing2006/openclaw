package com.wechatbot.fashion.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RealtimeSearchFallbackTest {

    @Test
    void marksBochaResultsAsFallbackData() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search("USD 兑 CNY 今日汇率")).thenReturn("博查搜索结果：\n1. 示例来源");

        String result = new RealtimeSearchFallback(objectProvider(provider))
                .search("汇率查询", "USD 兑 CNY 今日汇率", "未配置");

        assertThat(result).contains("已自动切换到公开网页搜索", "博查搜索结果：");
    }

    @Test
    void marksMcpResultsAsFallbackData() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search("今日汇率")).thenReturn("搜索结果：\n1. 示例来源\n来源：https://example.com");

        String result = new RealtimeSearchFallback(objectProvider(provider))
                .search("汇率查询", "今日汇率", "未配置");

        assertThat(result).contains("已自动切换到公开网页搜索", "搜索结果：");
    }

    @Test
    void reportsMissingProvider() {
        String result = new RealtimeSearchFallback(null)
                .search("汇率查询", "USD", "未配置");

        assertThat(result).isEqualTo("汇率查询暂时不可用：未配置，且搜索服务未配置。");
    }

    @Test
    void treatsProviderFailureAsUnavailable() {
        WebSearchProvider provider = mock(WebSearchProvider.class);
        when(provider.search("USD")).thenReturn("搜索失败：请稍后重试");

        String result = new RealtimeSearchFallback(objectProvider(provider))
                .search("汇率查询", "USD", "服务宕机");

        assertThat(result).startsWith("汇率查询暂时不可用：服务宕机。");
        assertThat(result).doesNotContain("已自动切换");
    }

    private static ObjectProvider<WebSearchProvider> objectProvider(WebSearchProvider provider) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("webSearchProvider", provider);
        return beanFactory.getBeanProvider(WebSearchProvider.class);
    }
}
