package com.example.ykdsummer.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection settings for the optional raw-file Responses provider. */
@ConfigurationProperties(prefix = "app.ai.responses")
public class OpenAiClientProperties {

    /** OpenAI-compatible service address. The official Java SDK requires the /v1 base path. */
    private String baseUrl = "https://api.openai.com/v1";

    /** Kept local or in deployment environment variables, never in source control. */
    private String apiKey = "not-configured";
    /** Responses model stays separate from the active Chat Completions model. */
    private String model = "not-configured";

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isConfigured() {
        return present(baseUrl) && present(apiKey) && present(model);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank() && !"not-configured".equalsIgnoreCase(value.strip());
    }
}
