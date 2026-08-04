package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.config.FashionTryOnProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Verifies the provider switch: only one VirtualTryOnService implementation is active at a time. */
class VirtualTryOnProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TryOnProviderContext.class);

    @Test
    void defaultsToReferenceImageProviderWhenUnconfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ReferenceImageVirtualTryOnService.class);
            assertThat(context).doesNotHaveBean(McpVirtualTryOnService.class);
        });
    }

    @Test
    void switchesToMcpProviderWhenConfigured() {
        runner.withPropertyValues("app.fashion.tryon.provider=mcp").run(context -> {
            assertThat(context).hasSingleBean(McpVirtualTryOnService.class);
            assertThat(context).doesNotHaveBean(ReferenceImageVirtualTryOnService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ReferenceImageVirtualTryOnService.class, McpVirtualTryOnService.class})
    static class TryOnProviderContext {
        @Bean
        LocalImageAssetStore assets() { return mock(LocalImageAssetStore.class); }

        @Bean
        AiImageGenerationService images() { return mock(AiImageGenerationService.class); }

        @Bean
        SyncMcpToolCallbackProvider toolProvider() { return mock(SyncMcpToolCallbackProvider.class); }

        @Bean
        FashionTryOnProperties tryOnProperties() { return new FashionTryOnProperties(); }
    }
}
