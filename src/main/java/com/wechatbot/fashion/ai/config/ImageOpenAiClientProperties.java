package com.wechatbot.fashion.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图片生成服务的独立 OpenAI Images API 连接参数。
 *
 * <p>文字模型使用 {@code openai.*} 与 Spring AI；本类只负责 {@code openai.image.*}。
 * 两套地址和密钥彼此独立，所以替换图片供应商不会影响微信文字问答。</p>
 */
@ConfigurationProperties(prefix = "openai.image")
public class ImageOpenAiClientProperties {

    /** OpenAI Images API 兼容地址，必须包含 /v1。 */
    private String baseUrl = "";

    /** 只能通过环境变量注入，不能提交到 Git。 */
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
