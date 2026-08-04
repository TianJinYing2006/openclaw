package com.example.ykdsummer.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 大模型功能的本地配置。
 *
 * <p>这里不保存 API Key。密钥单独绑定到 {@link OpenAiClientProperties}，
 * 业务处理类不会读取或输出密钥。</p>
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /**
     * 参考 TJY 分支的微信聊天风格，但保留事实边界和复杂问题的必要说明。
     * 普通 Completion 与 Responses 多模态请求共用这一条基础提示词。
     */
    public static final String DEFAULT_SYSTEM_PROMPT = "你是微信里的 AI 穿搭助手。用自然、简洁的简体中文回答，"
            + "优先帮用户完成穿什么、衣橱里有什么、衣服如何入库、试穿效果和定时提醒；不确定时说明不确定，绝不编造工具结果。"
            + "普通穿搭咨询先结合已知用户画像和个人衣橱；需要天气时查询天气。信息缺失时一次只追问完成当前任务必要的一项。"
            + "用户要求穿搭推荐（穿什么、怎么搭、帮我配/推荐/搭一套，含结合天气/季节/场合）时必须调用 fashion_consultant 工具"
            + "获取带参考图片的方案，不得直接输出穿搭文字；天气等查询只是前置步骤，查完后必须继续调用该工具。"
            + "个人衣橱永远优先于公共参考；只有用户明确要求灵感、参考款或公共搭配案例时才查询公共 Look。公共 Look 不是商品，"
            + "不得编造价格、库存或购买链接。商品橱窗尚未开放，不要承诺购买或下单。"
            + "用户用类目、颜色、风格、版型、图案、季节、场景或材质找衣服时，必须把所有已明确条件一并传给个人衣橱查询；"
            + "表达模糊的场景化需求时可用语义检索。需要看衣服图片时展示衣橱；用户可以用自然语言说左上、第一件或那条灰色裤子选择。"
            + "用户要求用某件已入库衣服生成整套穿搭时，先用 search_wardrobe 定位内部 wardrobeItemId，再调用 "
            + "recommend_outfits_from_wardrobe；需要天气时可在第一轮并行查询。不得自行遍历公共库、随机拼衣服或让 LLM 改写工具评分。"
            + "推荐工具中的公共 Look 只是搭配证据，最终方案只能使用当前用户自己的衣服；不足三套就返回真实数量，证据不足时明确说明。"
            + "推荐效果图由后台异步生成并主动回传，工具刚返回时不得声称图片已经发送；用户之后说第一套或第二套时，"
            + "必须依据内部持久化推荐状态定位，不得凭聊天记忆猜测。"
            + "用户要求识别、提取或加入服装照片时，先识别候选和完整度；衣服穿在人身上、轻微遮挡或局部裁切时，以识别结果为准，"
            + "只有工具明确要求重拍才请用户补图。必须展示候选和标签，等用户明确选择后再抠图；抠图或图片修改生成的多个版本都保留，"
            + "只有用户明确确认某一版本满意时才加入衣橱。颜色、名称、标签或图片需要调整时，基于当前候选修改，不能假装已完成。"
            + "用户上传全身照并明确要求保存时才创建试衣人物模板；不得推断身份、年龄、性别、体重、尺寸或其他敏感属性。"
            + "用户明确同意试穿某件已确认衣物时才提交后台试衣；完成图会主动回传，用户询问进度或失败原因时再查询任务状态。"
            + "用户提到刚才、上一张或某个图片版本时先查询已保存图片；生成新图、修改图片或回退版本必须调用图片工具，"
            + "没有成功结果前不得声称图片已生成或已发送。"
            + "用户要求未来提醒或到点执行任务时，先查询当前中国时间并创建微信定时任务；到点 Agent 会自行决定提醒或完成允许的工具操作。"
            + "所有内部 ID、候选编号、任务编号、英文枚举、向量分数和数据库信息只供工具链使用，绝不展示给用户。"
            + "当前没有提供的网页、文档、语音、飞书、娱乐或通用信息查询能力，不要假装可以完成。";

    /** 是否把普通微信消息交给大模型。固定命令不受此开关影响。 */
    private boolean enabled = true;

    /** 第三方服务实际接受的模型名称。使用字符串可兼容服务商的模型别名。 */
    private String model = "mimo-v2.5";

    /** 普通微信聊天的统一 system prompt，可用 AI_SYSTEM_PROMPT 覆盖。 */
    private String systemPrompt = DEFAULT_SYSTEM_PROMPT;

    /** Chat Completions 单次回答的输出上限；提示词负责简洁，上限只防止异常长输出。 */
    private int maxCompletionTokens = 800;

    /** 当前模型的推理强度；Chat Completions 与可选 Responses 通道都使用它。 */
    private String reasoningEffort = "medium";

    /**
     * 原始文件与旧 reasoning 请求是否允许使用 /v1/responses。
     * 默认关闭：当前百炼 Qwen 配置只验证了 Chat Completions，不能把不兼容请求静默发给它。
     */
    private boolean responsesEnabled;

    /** 单次模型请求最长等待时间。 */
    private Duration timeout = Duration.ofSeconds(120);

    /**
     * 图片识别不复用聊天的长等待和高推理强度。它只需要输出结构化视觉事实，超过一分钟应尽快交还控制权。
     */
    private Duration visionTimeout = Duration.ofSeconds(90);
    private int visionMaxCompletionTokens = 128;
    private String visionReasoningEffort = "medium";

    /** 一个 Agent 请求最多允许几轮“模型提出 Tool 调用 -> 执行 Tool”的规划；不限制单轮工具数量。 */
    private int maxAgentRounds = 4;

    /** 每个微信用户最多保留的用户/助手消息总条数。 */
    private int maxMemoryMessages = 20;

    /** 多久没有继续对话后清理该用户的内存记录。 */
    private Duration memoryIdleTimeout = Duration.ofHours(2);

    /** 单实例最多缓存多少位用户的短期对话，超出后由 Caffeine 近似 LRU 淘汰。 */
    private long maxMemoryUsers = 10_000L;

    /** 是否允许文字生成图片。 */
    private boolean imageEnabled = true;

    /** 图片视觉识别专用模型（如 qwen-vl-max）。默认回退到主模型。 */
    private String visionModel = "";

    /** 图片生成模型名称。当前活动客户端走 OpenAI Images 兼容的 gpt-image-2。 */
    private String imageModel = "gpt-image-2";

    /** 图片尺寸、质量和单次请求超时。 */
    private String imageSize = "1024x1024";
    private String imageQuality = "high";
    private Duration imageTimeout = Duration.ofSeconds(120);
    /** 异步图生图任务的状态查询间隔；网络偶发失败不会立刻判定任务失败。 */
    private Duration imagePollInterval = Duration.ofSeconds(3);

    /** 穿搭多 Agent 管道专用快速模型（如 qwen3.7-flash）；留空则回退到主模型。 */
    private String fashionModel = "";

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

    public String getFashionModel() {
        return fashionModel;
    }

    public void setFashionModel(String fashionModel) {
        this.fashionModel = fashionModel;
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

    public boolean isResponsesEnabled() {
        return responsesEnabled;
    }

    public void setResponsesEnabled(boolean responsesEnabled) {
        this.responsesEnabled = responsesEnabled;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getVisionTimeout() {
        return visionTimeout;
    }

    public void setVisionTimeout(Duration visionTimeout) {
        this.visionTimeout = visionTimeout == null || visionTimeout.isZero() || visionTimeout.isNegative()
                ? Duration.ofSeconds(90) : visionTimeout;
    }

    public int getVisionMaxCompletionTokens() {
        return visionMaxCompletionTokens;
    }

    public void setVisionMaxCompletionTokens(int visionMaxCompletionTokens) {
        this.visionMaxCompletionTokens = Math.max(128, visionMaxCompletionTokens);
    }

    public String getVisionReasoningEffort() {
        return visionReasoningEffort;
    }

    public void setVisionReasoningEffort(String visionReasoningEffort) {
        this.visionReasoningEffort = visionReasoningEffort == null ? "" : visionReasoningEffort.strip();
    }

    public int getMaxAgentRounds() {
        return maxAgentRounds;
    }

    public void setMaxAgentRounds(int maxAgentRounds) {
        this.maxAgentRounds = Math.max(1, Math.min(maxAgentRounds, 20));
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

    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }

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
    public Duration getImagePollInterval() { return imagePollInterval; }
    public void setImagePollInterval(Duration imagePollInterval) {
        this.imagePollInterval = imagePollInterval == null || imagePollInterval.isNegative() || imagePollInterval.isZero()
                ? Duration.ofSeconds(3) : imagePollInterval;
    }
}
