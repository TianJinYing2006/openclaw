package com.example.ykdsummer.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 官方 OpenAI Java 客户端的连接参数。Spring 从 openai.* 配置绑定到这里；
 * 它与 CC Switch/Codex 的配置互不相通，Java 程序必须自己获得 AI_API_KEY。
 */
@Component
@ConfigurationProperties(prefix = "openai")
public class OpenAiClientProperties {

    /** OpenAI 兼容服务地址。Java SDK 要求这里包含 /v1。 */
    private String baseUrl = "https://moosecloud.cc/v1";

    /** 只允许通过外部配置注入；仓库中永远不放真实值。 */
    private String apiKey = "not-configured";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
