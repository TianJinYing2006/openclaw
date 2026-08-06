package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.ykdsummer.ai.mcp.McpConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Verifies the provider switch: only one WebSearchProvider implementation is active at a time. */
class WebSearchProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WebSearchProviderContext.class);

    @Test
    void defaultsToBochaProviderWhenUnconfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(BochaWebSearchTools.class);
            assertThat(context).doesNotHaveBean(McpWebSearchTools.class);
        });
    }

    @Test
    void switchesToMcpProviderWhenConfigured() {
        runner.withPropertyValues("app.web-search.provider=mcp").run(context -> {
            assertThat(context).hasSingleBean(McpWebSearchTools.class);
            assertThat(context).doesNotHaveBean(BochaWebSearchTools.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({BochaWebSearchTools.class, McpWebSearchTools.class})
    static class WebSearchProviderContext {
        @Bean
        McpConnectionManager mcpManager() { return mock(McpConnectionManager.class); }

        @Bean
        WebSearchProperties webSearchProperties() { return new WebSearchProperties(); }
    }
}
