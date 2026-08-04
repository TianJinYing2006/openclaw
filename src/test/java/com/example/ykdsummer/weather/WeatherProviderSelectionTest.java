package com.example.ykdsummer.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/** 验证 provider 切换：同一时刻只有一个 WeatherProvider 实现处于激活状态。 */
class WeatherProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WeatherProviderContext.class);

    @Test
    void defaultsToUapisProviderWhenUnconfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WeatherService.class);
            assertThat(context).doesNotHaveBean(McpWeatherProvider.class);
        });
    }

    @Test
    void switchesToMcpProviderWhenConfigured() {
        runner.withPropertyValues("app.weather.provider=mcp").run(context -> {
            assertThat(context).hasSingleBean(McpWeatherProvider.class);
            assertThat(context).doesNotHaveBean(WeatherService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({WeatherService.class, McpWeatherProvider.class})
    static class WeatherProviderContext {
        @Bean
        RestClient.Builder restClientBuilder() { return RestClient.builder(); }

        @Bean
        SyncMcpToolCallbackProvider toolProvider() { return mock(SyncMcpToolCallbackProvider.class); }

        @Bean
        WeatherProperties weatherProperties() { return new WeatherProperties(); }
    }
}
