package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.exchange.ExchangeRateService;
import org.junit.jupiter.api.Test;

class ExchangeRateToolsTest {

    @Test
    void usesBochaWhenTheExchangeRateSourceFails() {
        ExchangeRateService exchangeRateService = mock(ExchangeRateService.class);
        RealtimeSearchFallback fallback = mock(RealtimeSearchFallback.class);
        when(exchangeRateService.convertCurrency("CNY", "USD", 1.0))
                .thenThrow(new IllegalStateException("offline"));
        when(fallback.search(eq("汇率查询"), contains("CNY 兑 USD"), eq("数据源暂时不可用")))
                .thenReturn("博查降级结果");

        Object result = new ExchangeRateTools(exchangeRateService, fallback)
                .convertCurrency("CNY", "USD", 1.0);

        assertThat(result).isEqualTo("博查降级结果");
        verify(fallback).search(eq("汇率查询"), contains("今日实时汇率"), eq("数据源暂时不可用"));
    }
}
