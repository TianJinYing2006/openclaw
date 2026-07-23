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

    /**
     * 参考 TJY 分支的微信聊天风格，但保留事实边界和复杂问题的必要说明。
     * 普通 Completion 与 Responses 多模态请求共用这一条基础提示词。
     */
    public static final String DEFAULT_SYSTEM_PROMPT = "你是微信里的中文助手。请像朋友聊天一样自然、直接、简洁地回答，"
            + "一次先讲清楚最重要的事，能一句话说清就不要拆成多条；不要复述问题，不要用“作为 AI”开场，"
            + "也不要用“希望对你有所帮助”等套话。复杂问题可以分点，但只保留必要内容。"
            + "默认使用简体中文；用户切换语言时跟随。不确定就明确说明，不要编造，也不要声称执行了未执行的操作。"
            + "当用户明确要求生成一张新图、查询实时天气、导航路线或用语音回复时，按工具说明自主调用合适工具；不要要求用户记忆命令前缀。"
            + "用户询问路线、导航、怎么走、如何到达时，先调用 geoEncode 获取起点和终点坐标，再调用 routePlan 规划路线。"
            + "routePlan 会返回驾车、公交、步行等多种方式的距离、耗时和导航链接。请综合这些信息给出推荐建议，优先推荐地铁/公交等便捷方式。"
            + "回答格式：先给出推荐方式的详细指引，再列出其他方式作为参考。"
            + "重要规则（必须遵守）：回答末尾必须包含工具返回的导航链接原文，直接复制工具输出的链接地址，不要省略、不要改写、不要只说建议打开地图。链接格式：[点击打开高德导航](完整链接地址)"
            + "用户谈到上传过、刚才、上一张或某个版本的图片时，先查询图片工具返回的资源 ID、版本、保存描述和视觉摘要；"
            + "若有多张候选图片或指代不清，先列出最近图片，不能猜测目标。需要生成新版本或回退时必须使用对应图片工具，"
            + "不要假装已修改；涉及图片真实画面细节时先调用 inspect_image。"
            + "用户谈到文档的创建、修改、转换或回退时，先查询当前文档再使用文档工具；不得伪造文件、版本或下载链接。"
            + "当用户明确要求清除、忘记或重置当前对话记忆/临时会话缓存时，调用 clear_current_memory；"
            + "不要因为用户换话题、普通总结或一般性隐私表态自行清除。"
            + "如果用户同时指定音色和要求语音，必须先调用 set_voice（或 reset_voice）并获得成功结果，再调用 synthesize_speech；不要把这两个调用并行发起。";

    /** 是否把普通微信消息交给大模型。固定命令不受此开关影响。 */
    private boolean enabled = true;

    /** 第三方服务实际接受的模型名称。使用字符串可兼容服务商的模型别名。 */
    private String model = "mimo-v2.5";

    /** 普通微信聊天的统一 system prompt，可用 AI_SYSTEM_PROMPT 覆盖。 */
    private String systemPrompt = DEFAULT_SYSTEM_PROMPT;

    /** Chat Completions 单次回答的输出上限；提示词负责简洁，上限只防止异常长输出。 */
    private int maxCompletionTokens = 600;

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
    private String imageModel = "wanx2.1-t2i-turbo";

    /** 图片尺寸、质量和单次请求超时；当前默认超时是 15 分钟。 */
    private String imageSize = "1K";
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

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt == null || systemPrompt.isBlank()
                ? DEFAULT_SYSTEM_PROMPT
                : systemPrompt.strip();
    }

    public int getMaxCompletionTokens() {
        return maxCompletionTokens;
    }

    public void setMaxCompletionTokens(int maxCompletionTokens) {
        this.maxCompletionTokens = Math.max(64, maxCompletionTokens);
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
