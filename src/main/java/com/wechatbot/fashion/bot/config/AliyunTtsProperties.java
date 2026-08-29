package com.wechatbot.fashion.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 阿里云百炼非实时语音合成配置；API Key 只能通过运行环境注入。 */
@ConfigurationProperties(prefix = "app.tts")
public class AliyunTtsProperties {

    private static final String NOT_CONFIGURED = "not-configured";

    private boolean enabled;
    private String apiKey = NOT_CONFIGURED;
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";
    private String model = "cosyvoice-v3-flash";
    private String voice = "longanyang";
    private String format = "mp3";
    private int sampleRate = 24_000;
    private Duration timeout = Duration.ofSeconds(90);
    private int maxTextLength = 600;
    private int maxAudioBytes = 10 * 1024 * 1024;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !NOT_CONFIGURED.equals(apiKey)
                && baseUrl != null && !baseUrl.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxTextLength() { return maxTextLength; }
    public void setMaxTextLength(int maxTextLength) { this.maxTextLength = Math.max(1, maxTextLength); }
    public int getMaxAudioBytes() { return maxAudioBytes; }
    public void setMaxAudioBytes(int maxAudioBytes) { this.maxAudioBytes = Math.max(1024, maxAudioBytes); }
}
