package com.example.ykdsummer.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型用量与预算的本地保护配置。
 *
 * <p>它不保存密钥，也不决定模型回答内容。它只决定“一次请求最多允许输出多少 token”以及
 * “单个微信用户在当天最多可以占用多少 token”。计数只留在当前 Java 进程内存，重启后清空；
 * 后续接 SQL 时可替换 {@code AiUsageMeter} 的存储实现。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.ai.usage")
public class AiUsageProperties {

    /** 是否启用预算保护；关闭后仍可记录网关信息，但不会拒绝请求。 */
    private boolean enabled = true;
    /** 单个微信用户、单个自然日可占用的 token 上限。0 表示不限制。 */
    private long dailyTokenLimit = 30_000L;
    /** 短文本闲聊的最大输出 token。 */
    private int simpleMaxOutputTokens = 300;
    /** 普通问答的最大输出 token。 */
    private int standardMaxOutputTokens = 600;
    /** 图片、文件或长分析任务的最大输出 token。 */
    private int complexMaxOutputTokens = 1_000;
    /** 允许进入模型前，本轮输入的估算 token 上限，避免超大文件一次打满额度。 */
    private long maxEstimatedInputTokens = 12_000L;
    /** 短文本闲聊的字符阈值；超过后至少按普通问答处理。 */
    private int simplePromptCharacters = 80;
    /** 内存中最多维护多少位用户当天的额度记录。 */
    private long maxTrackedUsers = 10_000L;
    /** 每张图片的保守输入 token 预估值，只用于预算预留，不冒充服务商真实 usage。 */
    private int estimatedTokensPerImage = 1_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getDailyTokenLimit() { return dailyTokenLimit; }
    public void setDailyTokenLimit(long dailyTokenLimit) { this.dailyTokenLimit = Math.max(0L, dailyTokenLimit); }
    public int getSimpleMaxOutputTokens() { return simpleMaxOutputTokens; }
    public void setSimpleMaxOutputTokens(int value) { this.simpleMaxOutputTokens = positive(value, 300); }
    public int getStandardMaxOutputTokens() { return standardMaxOutputTokens; }
    public void setStandardMaxOutputTokens(int value) { this.standardMaxOutputTokens = positive(value, 600); }
    public int getComplexMaxOutputTokens() { return complexMaxOutputTokens; }
    public void setComplexMaxOutputTokens(int value) { this.complexMaxOutputTokens = positive(value, 1_000); }
    public long getMaxEstimatedInputTokens() { return maxEstimatedInputTokens; }
    public void setMaxEstimatedInputTokens(long value) { this.maxEstimatedInputTokens = Math.max(1L, value); }
    public int getSimplePromptCharacters() { return simplePromptCharacters; }
    public void setSimplePromptCharacters(int value) { this.simplePromptCharacters = positive(value, 80); }
    public long getMaxTrackedUsers() { return maxTrackedUsers; }
    public void setMaxTrackedUsers(long value) { this.maxTrackedUsers = Math.max(1L, value); }
    public int getEstimatedTokensPerImage() { return estimatedTokensPerImage; }
    public void setEstimatedTokensPerImage(int value) { this.estimatedTokensPerImage = positive(value, 1_000); }

    private static int positive(int value, int fallback) { return value > 0 ? value : fallback; }
}
