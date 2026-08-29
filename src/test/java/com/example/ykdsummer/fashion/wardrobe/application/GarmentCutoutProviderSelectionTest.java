package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.ykdsummer.ai.mcp.McpConnectionManager;
import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.wardrobe.config.FashionCutoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 验证 provider 切换：同一时刻只有一个 GarmentCutoutService 实现处于激活状态。 */
class GarmentCutoutProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GarmentCutoutProviderContext.class);

    @Test
    void defaultsToReferenceImageProviderWhenUnconfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ReferenceImageGarmentCutoutService.class);
            assertThat(context).doesNotHaveBean(McpGarmentCutoutService.class);
        });
    }

    @Test
    void switchesToMcpProviderWhenConfigured() {
        runner.withPropertyValues("app.fashion.cutout.provider=mcp").run(context -> {
            assertThat(context).hasSingleBean(McpGarmentCutoutService.class);
            assertThat(context).doesNotHaveBean(ReferenceImageGarmentCutoutService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ReferenceImageGarmentCutoutService.class, McpGarmentCutoutService.class})
    static class GarmentCutoutProviderContext {
        @Bean
        AiImageGenerationService aiImageGenerationService() { return mock(AiImageGenerationService.class); }

        @Bean
        LocalImageAssetStore localImageAssetStore() { return mock(LocalImageAssetStore.class); }

        @Bean
        McpConnectionManager mcpManager() { return mock(McpConnectionManager.class); }

        @Bean
        FashionCutoutProperties fashionCutoutProperties() { return new FashionCutoutProperties(); }
    }
}
