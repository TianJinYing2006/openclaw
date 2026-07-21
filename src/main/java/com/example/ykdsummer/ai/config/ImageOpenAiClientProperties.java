package com.example.ykdsummer.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图片生成服务的独立连接参数。
 *
 * <p>文字模型继续使用 {@code openai.*}，图片生成只使用 {@code openai.image.*}。
 * 这样更换图片供应商时不会影响普通文字对话。</p>
 */
@Component
@ConfigurationProperties(prefix = "openai.image")
public class ImageOpenAiClientProperties {

    /** OpenAI Images API 兼容地址，必须包含 /v1。 */
    private String baseUrl = "https://api.lk888.ai/v1";

    /** 只能由外部环境变量提供，仓库中不保存真实密钥。 */
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
