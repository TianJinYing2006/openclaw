package com.example.ykdsummer.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 大模型功能的本地配置。
 *
 * <p>这里不保存 API Key。密钥单独绑定到 {@link OpenAiClientProperties}，
 * 业务处理类不会读取或输出密钥。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 是否把普通微信消息交给大模型。固定命令不受此开关影响。 */
    private boolean enabled = true;

    /** 第三方服务实际接受的模型名称。使用字符串可兼容服务商的模型别名。 */
    private String model = "gpt-5.6-sol";

    /** Responses API 的 reasoning.effort。 */
    private String reasoningEffort = "high";

    /** 单次模型请求最长等待时间。 */
    private Duration timeout = Duration.ofSeconds(120);

    /** 每个微信用户最多保留的用户/助手消息总条数。 */
    private int maxMemoryMessages = 20;

    /** 多久没有继续对话后清理该用户的内存记录。 */
    private Duration memoryIdleTimeout = Duration.ofHours(2);

    /** 单实例最多缓存多少位用户的短期对话，超出后由 Caffeine 近似 LRU 淘汰。 */
    private long maxMemoryUsers = 10_000L;

    /** 是否允许文字生成图片。 */
    private boolean imageEnabled = true;

    /** 图片生成模型名称。 */
    private String imageModel = "gpt-image-2";

    /** 图片尺寸、质量和单次请求超时；当前默认超时是 15 分钟。 */
    private String imageSize = "1024x1024";
    private String imageQuality = "medium";
    private Duration imageTimeout = Duration.ofMinutes(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxMemoryMessages() {
        return maxMemoryMessages;
    }

    public void setMaxMemoryMessages(int maxMemoryMessages) {
        this.maxMemoryMessages = Math.max(2, maxMemoryMessages);
    }

    public Duration getMemoryIdleTimeout() {
        return memoryIdleTimeout;
    }

    public void setMemoryIdleTimeout(Duration memoryIdleTimeout) {
        this.memoryIdleTimeout = memoryIdleTimeout;
    }

    public long getMaxMemoryUsers() {
        return maxMemoryUsers;
    }

    public void setMaxMemoryUsers(long maxMemoryUsers) {
        this.maxMemoryUsers = Math.max(1L, maxMemoryUsers);
    }

    public boolean isImageEnabled() { return imageEnabled; }
    public void setImageEnabled(boolean imageEnabled) { this.imageEnabled = imageEnabled; }
    public String getImageModel() { return imageModel; }
    public void setImageModel(String imageModel) { this.imageModel = imageModel; }
    public String getImageSize() { return imageSize; }
    public void setImageSize(String imageSize) { this.imageSize = imageSize; }
    public String getImageQuality() { return imageQuality; }
    public void setImageQuality(String imageQuality) { this.imageQuality = imageQuality; }
    public Duration getImageTimeout() { return imageTimeout; }
    public void setImageTimeout(Duration imageTimeout) { this.imageTimeout = imageTimeout; }
}
