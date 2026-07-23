package com.example.ykdsummer.ai.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 创建全应用共享的 OpenAI HTTP 客户端，并开启会话过期清理任务。
 * {@link Bean} 方法的返回对象会由 Spring 保存，供 Responses 网关使用。
 */
@Configuration
@EnableScheduling
public class OpenAiClientConfiguration {

    @Bean
    @Primary
    public OpenAIClient openAIClient(
            OpenAiClientProperties clientProperties,
            AiProperties aiProperties
    ) {
        /*
         * 构造客户端时只保存地址、Key、超时等连接设置，不会立即调用模型。真正的网络请求
         * 分别发生在 client.responses().create(...) 和 client.images().generate(...)。
         */
        return OpenAIOkHttpClient.builder()
                .baseUrl(normalizeBaseUrl(clientProperties.getBaseUrl()))
                .apiKey(clientProperties.getApiKey())
                .timeout(aiProperties.getTimeout())
                // 不自动重试；120 秒上限主要给全文文档重构留出输出时间。
                // 普通文字通常会更快完成，图片服务仍使用自己的独立超时。
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
        String defaultValue = "https://moosecloud.cc/v1";
        // 允许环境变量省略 /v1，最终统一成 OpenAI Java SDK 所需的基础地址。
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }
}
