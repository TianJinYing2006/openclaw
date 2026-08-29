package com.wechatbot.fashion.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 腾讯云一句话识别配置。
 *
 * <p>SecretId 和 SecretKey 只允许从运行环境传入，不能写进源码或配置文件。</p>
 */
@ConfigurationProperties(prefix = "app.asr")
public class TencentAsrProperties {

    private static final String NOT_CONFIGURED = "not-configured";

    /** 是否尝试转写视频音轨；关闭后视频仍会继续进行 10 帧画面分析。 */
    private boolean enabled = true;

    private String secretId = NOT_CONFIGURED;
    private String secretKey = NOT_CONFIGURED;

    /** 中文普通话 16k 引擎；视频音轨会先被 FFmpeg 统一转换为 16kHz。 */
    private String engineServiceType = "16k_zh";
    private String voiceFormat = "wav";
    private String endpoint = "asr.tencentcloudapi.com";
    private String region = "";
    private Duration timeout = Duration.ofSeconds(30);

    /** 一句话识别官方限制为 3 MiB。 */
    private DataSize maxAudioSize = DataSize.ofMegabytes(3);

    public boolean isConfigured() {
        return hasRealValue(secretId) && hasRealValue(secretKey);
    }

    private static boolean hasRealValue(String value) {
        return value != null && !value.isBlank() && !NOT_CONFIGURED.equals(value);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecretId() {
        return secretId;
    }

    public void setSecretId(String secretId) {
        this.secretId = secretId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getEngineServiceType() {
        return engineServiceType;
    }

    public void setEngineServiceType(String engineServiceType) {
        this.engineServiceType = engineServiceType;
    }

    public String getVoiceFormat() {
        return voiceFormat;
    }

    public void setVoiceFormat(String voiceFormat) {
        this.voiceFormat = voiceFormat;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public DataSize getMaxAudioSize() {
        return maxAudioSize;
    }

    public void setMaxAudioSize(DataSize maxAudioSize) {
        this.maxAudioSize = maxAudioSize;
    }
}
