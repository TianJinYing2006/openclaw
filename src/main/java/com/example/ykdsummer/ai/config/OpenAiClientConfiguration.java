package com.example.ykdsummer.ai.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Creates the optional Responses HTTP client and enables scheduled cleanup tasks.
 */
@Configuration
@EnableScheduling
public class OpenAiClientConfiguration {

    @Bean
    @Primary
    public OpenAIClient responsesOpenAIClient(
            OpenAiClientProperties clientProperties,
            AiProperties aiProperties
    ) {
        /*
         * Construction does not call a model. Routing rejects incomplete Responses configuration before
         * this client can make a network request.
         */
        return OpenAIOkHttpClient.builder()
                .baseUrl(normalizeBaseUrl(clientProperties.getBaseUrl()))
                .apiKey(clientProperties.getApiKey())
                .timeout(aiProperties.getTimeout())
                .maxRetries(0)
                .build();
    }

    /**
     * 图片与文字使用不同的 OpenAI 兼容网关。该 Bean 只会在 ImageTools 真正调用生图时访问网络，
     * 不会影响 Responses、Chat Completions 或 iLink 长轮询。
     */
    @Bean("imageOpenAIClient")
    public OpenAIClient imageOpenAIClient(
            ImageOpenAiClientProperties imageProperties,
            AiProperties aiProperties
    ) {
        return OpenAIOkHttpClient.builder()
                .baseUrl(normalizeBaseUrl(imageProperties.getBaseUrl()))
                .apiKey(imageProperties.getApiKey())
                .timeout(aiProperties.getImageTimeout())
                .maxRetries(0)
                .build();
    }

    private static String normalizeBaseUrl(String value) {
        String defaultValue = "https://api.openai.com/v1";
        // Allow an environment variable to omit /v1 while keeping a valid inert default when disabled.
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }
}
