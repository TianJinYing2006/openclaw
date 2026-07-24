package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class RealtimeSearchFallbackTest {

    @Test
    void marksBochaResultsAsFallbackData() {
        BochaWebSearchTools bocha = mock(BochaWebSearchTools.class);
        when(bocha.searchWeb("USD 兑 CNY 今日汇率")).thenReturn("博查搜索结果：\n1. 示例来源");

        String result = new RealtimeSearchFallback(bocha)
                .search("汇率查询", "USD 兑 CNY 今日汇率", "未配置");

        assertThat(result).contains("已自动切换到博查", "博查搜索结果：");
    }
}
