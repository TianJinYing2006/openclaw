package com.wechatbot.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.wechatbot.fashion.ai.mcp.McpConnectionManager;
import com.wechatbot.fashion.ai.service.ImageInspectionService;
import com.wechatbot.fashion.ai.service.LocalImageAssetStore;
import com.wechatbot.fashion.wardrobe.config.FashionAnalysisProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 验证 provider 切换：同一时刻只有一个 WardrobePhotoAnalyzer 实现处于激活状态。 */
class WardrobePhotoAnalyzerProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WardrobePhotoAnalyzerContext.class);

    @Test
    void defaultsToChatCompletionsProviderWhenUnconfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(FashionVisionCandidateAnalyzer.class);
            assertThat(context).doesNotHaveBean(McpWardrobePhotoAnalyzer.class);
        });
    }

    @Test
    void switchesToMcpProviderWhenConfigured() {
        runner.withPropertyValues("app.fashion.analysis.provider=mcp").run(context -> {
            assertThat(context).hasSingleBean(McpWardrobePhotoAnalyzer.class);
            assertThat(context).doesNotHaveBean(FashionVisionCandidateAnalyzer.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({FashionVisionCandidateAnalyzer.class, McpWardrobePhotoAnalyzer.class})
    static class WardrobePhotoAnalyzerContext {
        @Bean
        ImageInspectionService imageInspectionService() { return mock(ImageInspectionService.class); }

        @Bean
        ObjectMapper objectMapper() { return new ObjectMapper(); }

        @Bean
        LocalImageAssetStore localImageAssetStore() { return mock(LocalImageAssetStore.class); }

        @Bean
        McpConnectionManager mcpManager() { return mock(McpConnectionManager.class); }

        @Bean
        FashionAnalysisProperties fashionAnalysisProperties() { return new FashionAnalysisProperties(); }
    }
}
