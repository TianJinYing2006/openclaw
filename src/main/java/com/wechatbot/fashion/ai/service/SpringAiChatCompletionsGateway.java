package com.wechatbot.fashion.ai.service;

import com.wechatbot.fashion.ai.config.AiProperties;
import com.wechatbot.fashion.ai.fashion.look.profile.FashionConversationService;
import com.wechatbot.fashion.ai.model.ConversationMessage;
import com.wechatbot.fashion.ai.orchestration.BoundedToolCallingManager;
import com.wechatbot.fashion.ai.orchestration.GovernedToolCallback;
import com.wechatbot.fashion.ai.orchestration.ToolCallScope;
import com.wechatbot.fashion.graph.budget.RunBudgetTracker;
import com.wechatbot.fashion.graph.trajectory.AgentTrajectoryRecorder;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import com.wechatbot.fashion.ai.orchestration.ToolRegistry;
import com.wechatbot.fashion.ai.tool.ToolArtifactCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用 Spring AI 1.1.8 调用 OpenAI Chat Completions 兼容接口（纯文本通道）。
 *
 * <p>由 {@link RoutingLlmGateway} 在检测到<strong>仅含文字</strong>时路由至此。
 * 它将 Java 内存中的 USER/ASSISTANT 历史转换为 Spring AI Message，并在最前面加入
 * 统一 system prompt。所有工具通过 {@link ToolRegistry} 自动注册。</p>
 *
 * <p>含图片/文件的多模态请求路由至 {@link OpenAiResponsesGateway}（Responses API）。</p>
 */
@Service
public class SpringAiChatCompletionsGateway implements TextChatGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatCompletionsGateway.class);

    /** 穿搭推荐工具名：模型漏调时网关兜底补调，保证推荐带参考图。 */
    private static final String FASHION_CONSULT_TOOL = "fashion_consultant";

    /** 已属于穿搭域的其它工具：若模型已调用过任一，说明正在处理衣橱/试穿链路，不再补调推荐。 */
    private static final Set<String> FASHION_DOMAIN_TOOLS = Set.of(
            "fashion_consultant", "search_wardrobe", "search_wardrobe_semantic",
            "add_wardrobe_item", "delete_wardrobe_item", "purge_wardrobe_item",
            "analyze_wardrobe_photo", "submit_garment_cutout", "confirm_wardrobe_candidate",
            "edit_garment_draft", "retry_garment_cutout", "virtual_try_on_reference_outfit",
            "virtual_try_on_wardrobe_item", "check_virtual_tryon_status",
            "select_wardrobe_preview_item", "show_wardrobe_items",
            "show_current_tryon_template", "list_person_tryon_templates",
            "save_person_tryon_template", "select_person_tryon_template");

    /** 穿搭推荐意图触发词（针对本轮用户输入）；"推荐"需与穿搭语义词共现，避免"推荐一部电影"误触发。 */
    private static final Pattern FASHION_CONSULT_INTENT = Pattern.compile(
            "搭配|穿什么|穿啥|怎么穿|咋穿|配一套|配一身|搭一套|穿搭|着装|衣品|场合|通勤穿|上班穿|面试穿"
                    + "|约会穿|旅行穿|运动穿|打球穿|爬山穿|跑步穿|去海边|去婚礼|见客户"
                    + "|推荐.{0,12}(衣服|一身|一套|裤子|裙子|外套|上衣|T恤|衬衫|通勤|上班|约会|面试|婚礼|海边|旅行|打球|跑步|爬山|运动)");

    /** 指向衣橱入库/试穿/图片操作的消息，不应触发穿搭推荐补调；"(?<!面)试穿"避免误伤"面试穿"。 */
    private static final Pattern FASHION_CONSULT_EXCLUDE = Pattern.compile(
            "衣橱|衣柜|入库|加入衣橱|(?<!面)试穿|穿一下|试试|上身效果|试衣|照片|图片|这件|那条");

    /** 试穿工具名：模型漏调（只口头承诺试穿、未执行）时网关兜底补调，保证用户能收到效果图。 */
    private static final String TRY_ON_TOOL = "virtual_try_on_reference_outfit";

    /** 试穿执行意图触发词（针对本轮用户输入）；"(?<!面)试穿"避免误伤"面试穿"。
     *  "换上/换一下/换这身"等换装表达也计入：模型常按上下文把它们理解成试穿，
     *  若路由不给试穿工具会导致只承诺"试穿任务"却无工具可调（8.27）。"换一套/再换一套"
     *  保持走推荐（fashion_consultant 在 core 组），不在此列。 */
    private static final Pattern TRY_ON_INTENT = Pattern.compile(
            "(?<!面)试穿|穿一下|穿穿|上身效果|穿上看看|试试|试一下|试一试|试下"
                    + "|换上|换一下|换这身|换这一身");

    /** 指向衣橱单品、疑问、图片操作的消息，不应触发推荐方案试穿补调（衣橱单品走 wardrobe_item 链路）。 */
    private static final Pattern TRY_ON_EXCLUDE = Pattern.compile(
            "衣橱|衣柜|衣柜里|加入衣橱|这件衣服|怎么试穿|如何试穿|试穿(流程|要钱|收费|怎么|吗)|可以试穿|支持试穿|照片|图片");

    /** 衣橱入库/抠图意图（含候选确认表达）：命中时提供衣橱入库工具组。 */
    private static final Pattern INTENT_WARDROBE_INTAKE = Pattern.compile(
            "入库|放进衣橱|放进衣柜|加入衣橱|加入衣柜|帮我加入|抠图|识别.*(照片|衣服)|上传.*(衣服|照片)"
                    + "|把.*(照片|图|衣服).*(衣橱|衣柜|入库)|这件.*(入库|放进)|这张图.*(衣橱|衣柜|入库)"
                    + "|只要(?!求)|就要|只存|只留|只加入|只入|只保留|选第一件|选这件|选.*件|都要|确认入库|就这件|这件可以|抠图吧"
                    + "|确认|确认一下|就它了|就它|就这件吧|同意");

    /** 衣橱查看/筛选意图：命中时提供衣橱查看工具组。 */
    private static final Pattern INTENT_WARDROBE_VIEW = Pattern.compile(
            "衣橱|衣柜|我的衣服|找.*衣服|筛选|看看.*(衣服|衣柜|衣橱)|有什么衣服|都有什么");

    /** 用户画像/偏好查询意图：命中时挂衣橱查看组（含 get_fashion_profile），
     *  让模型读取真实画像而非凭历史聊天猜测（8.29：用户问"我当前的用户画像"时只挂 core，
     *  模型无画像工具可用，只能猜测）。 */
    private static final Pattern INTENT_FASHION_PROFILE = Pattern.compile(
            "我的画像|用户画像|我的偏好|我的风格|我的预算|我的穿衣风格|我的穿搭习惯|我的喜好|个人画像"
                    + "|我是什么风格|我适合什么风格|了解我|我的衣服风格|我的穿搭风格");

    /** 定时提醒意图：命中时提供提醒工具组。 */
    private static final Pattern INTENT_REMINDER = Pattern.compile(
            "提醒|定时|到点|设个|每天.*提醒|明天.*(提醒|叫我|叫醒)|几分钟后|到时候");

    /** 衣橱单品词：配合试穿意图判定"用户指向衣橱具体单品"（如"试一下灰色T恤"）。 */
    private static final Pattern WARDROBE_ITEM_WORDS = Pattern.compile(
            "T恤|衬衫|外套|卫衣|毛衣|裤子|裤|裙|鞋|帽子|背心|风衣|大衣|T 恤|那件|这件|灰色|黑色|白色|红色|蓝色");

    private final ChatClient chatClient;
    private final AiProperties properties;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;
    /** 生产环境通过 ToolRegistry 自动发现所有 @Tool Bean；测试环境通过构造函数传入。 */
    private final ToolRegistry toolRegistry;
    private final Object[] testToolBeans;
    /**
     * 生产环境注入的 Agent 轮次守卫。generate() 结束后在 finally 中调用 clearRequest()，
     * 防止池化线程复用时 ThreadLocal 的轮次计数跨请求累积导致后续请求立即触发上限异常。
     * 测试环境为 null，因为测试用 Mock ChatModel 不经过 ToolCallingManager。
     */
    private final BoundedToolCallingManager toolCallingManager;
    /** 最近推荐编号查询（试穿漏调兜底取编号用）；{@code @Autowired(required=false)} 保持测试构造不受影响。 */
    private volatile FashionConversationService fashionConversationService;
    /** 最近上传图片查询（照片入库漏调兜底取 img_ 编号用）；{@code @Autowired(required=false)} 保持测试构造不受影响。 */
    private volatile LocalImageAssetStore imageStore;
    /** 衣橱入库流程（兜底判断照片是否已有识别候选，决定补调识别还是提交抠图）；{@code @Autowired(required=false)}。 */
    private volatile com.wechatbot.fashion.wardrobe.application.FashionWardrobeIngestionService wardrobeIngestion;
    /** 工具治理：run 预算（图外工具调用也纳入；未装配时仅超时生效）。 */
    private volatile RunBudgetTracker runBudgetTracker;
    /** 工具治理：工具调用审计写入轨迹。 */
    private volatile AgentTrajectoryRecorder trajectoryRecorder;
    /** 工具治理：单工具超时执行器。 */
    private volatile ExecutorService toolTimeoutExecutor;

    @Autowired(required = false)
    public void setRunBudgetTracker(RunBudgetTracker runBudgetTracker) {
        this.runBudgetTracker = runBudgetTracker;
    }

    @Autowired(required = false)
    public void setTrajectoryRecorder(AgentTrajectoryRecorder trajectoryRecorder) {
        this.trajectoryRecorder = trajectoryRecorder;
    }

    @Autowired(required = false)
    public void setToolTimeoutExecutor(
            @Qualifier("fashionAgentParallelExecutor") ExecutorService toolTimeoutExecutor) {
        this.toolTimeoutExecutor = toolTimeoutExecutor;
    }

    /**
     * 生产环境构造器：工具由 {@link ToolRegistry} 自动扫描注册。
     * 注意：ToolRegistry 在 {@code ContextRefreshedEvent} 后才完成扫描，
     * 因此 {@code toolRegistry.allToolBeans()} 在构造时不可用，需在 {@link #generate} 中懒调用。
     */
    @Autowired
    public SpringAiChatCompletionsGateway(
            ChatModel chatModel,
            AiProperties properties,
            ToolRegistry toolRegistry,
            ObjectProvider<BoundedToolCallingManager> toolCallingManagerProvider,
            @Autowired(required = false) ToolArtifactCollector artifactCollector,
            @Autowired(required = false) AiTraceLogger trace
    ) {
        this.chatClient = ChatClient.create(chatModel);
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.testToolBeans = null;
        this.toolCallingManager = toolCallingManagerProvider.getIfAvailable();
        this.artifacts = artifactCollector;
        this.trace = trace != null ? trace : AiTraceLogger.disabled();
    }

    @Autowired(required = false)
    public void setFashionConversationService(FashionConversationService service) {
        this.fashionConversationService = service;
    }

    @Autowired(required = false)
    public void setImageStore(LocalImageAssetStore store) {
        this.imageStore = store;
    }

    @Autowired(required = false)
    public void setWardrobeIngestion(
            com.wechatbot.fashion.wardrobe.application.FashionWardrobeIngestionService service) {
        this.wardrobeIngestion = service;
    }

    /**
     * 测试环境构造器：工具通过 {@code toolBeans} 数组显式传入，不受 Spring 上下文限制。
     */
    SpringAiChatCompletionsGateway(
            ChatModel chatModel,
            AiProperties properties,
            Object[] toolBeans,
            ToolArtifactCollector artifactCollector,
            AiTraceLogger trace
    ) {
        this.chatClient = ChatClient.create(chatModel);
        this.properties = properties;
        this.toolRegistry = null;
        this.testToolBeans = toolBeans != null ? toolBeans : new Object[0];
        this.toolCallingManager = null;
        this.artifacts = artifactCollector;
        this.trace = trace != null ? trace : AiTraceLogger.disabled();
    }

    @Override
    public LlmGateway.ModelReply generate(List<ConversationMessage> history, String prompt) {
        return generate("unknown", history, prompt, null);
    }

    @Override
    public LlmGateway.ModelReply generate(String userId, List<ConversationMessage> history, String prompt) {
        return generate(userId, history, prompt, null);
    }

    @Override
    public LlmGateway.ModelReply generate(
            String userId, List<ConversationMessage> history, String prompt, AiRequestBudget budget
    ) {
        // 工具治理：每次对话请求建立一个 run 作用域，使图外工具调用也纳入预算与审计
        String toolRunId = UUID.randomUUID().toString();
        long toolRunStart = System.currentTimeMillis();
        try {
            if (artifacts != null) {
                artifacts.begin(userId);
            }
            ToolCallScope.begin(toolRunId);
            if (runBudgetTracker != null) {
                runBudgetTracker.begin(toolRunId);
            }
            if (trajectoryRecorder != null) {
                trajectoryRecorder.startRun(new AgentTrajectoryRecorder.RunStart(
                        toolRunId, userId, userId, prompt, "chat-gateway-v1", "n/a", null));
            }
            Object[] tools = resolveTools(prompt);
            var request = chatClient.prompt(buildPrompt(history, prompt, outputLimit(budget)));
            if (tools.length > 0) {
                request = request.toolCallbacks(governedToolCallbacks(tools));
            }
            ChatResponse response = request
                    .call()
                    .chatResponse();
            String text = extractText(response);
            if (text.isBlank()) {
                // 工具调用轮（模型本轮只有 tool_calls 无文本）由 Spring AI 内部工具循环继续处理，不重试；
                // 无工具调用的真空响应多为偶发，重试一次避免直接打断用户请求。
                if (!hasToolCalls(response)) {
                    log.warn("Chat completion returned empty text without tool calls, retrying once: usage={}",
                            extractUsage(response));
                    response = request.call().chatResponse();
                    text = extractText(response);
                }
            }
            if (text.isBlank()) {
                log.warn("Chat completion returned empty text after retry: finishReason={}, toolCalls={}, usage={}",
                        finishReason(response), hasToolCalls(response), extractUsage(response));
                throw new AiGatewayException(AiGatewayException.Kind.EMPTY_RESPONSE);
            }
            // 兜底：模型漏调 fashion_consultant（如先查天气后直接输出文字）时自动补调，保证推荐带参考图
            text = maybeAutoConsult(userId, prompt, text);
            // 兜底：模型漏调试穿工具（只口头承诺"正在试穿"未执行）时自动补调最近推荐方案，保证用户能收到效果图
            text = maybeAutoTryOn(userId, prompt, text);
            // 兜底：衣橱单品意图被错调成推荐方案试穿时追加纠正提示，避免用户收到错误效果图而无感知
            text = maybeCorrectWardrobeMisroute(userId, prompt, text);
            // 兜底：照片入库意图被模型只回承诺文案（"正在提取/正在抠图"）而未调入库工具时，
            // 自动补调 analyze_wardrobe_photo（取最近上传照片），保证候选真的生成并推送给用户
            text = maybeAutoWardrobeIntake(userId, prompt, text);
            String actualModel = response.getMetadata() == null || response.getMetadata().getModel() == null
                    || response.getMetadata().getModel().isBlank()
                    ? properties.getModel()
                    : response.getMetadata().getModel();
            log.info("Spring AI chat completion completed, model={}", actualModel);
            trace.modelReply("Chat Completions", actualModel, text);
            return new LlmGateway.ModelReply(
                    text,
                    actualModel,
                    artifacts == null ? List.of() : artifacts.finish(),
                    extractUsage(response),
                    "chat-completions"
            );
        } catch (AiGatewayException exception) {
            if (artifacts != null) artifacts.discard();
            throw exception;
        } catch (RuntimeException exception) {
            if (artifacts != null) artifacts.discard();
            trace.failure("Chat Completions (/v1/chat/completions)", exception);
            AiGatewayException.Kind kind = isAuthenticationFailure(exception)
                    ? AiGatewayException.Kind.AUTHENTICATION
                    : AiGatewayException.Kind.TEMPORARY_UNAVAILABLE;
            throw new AiGatewayException(kind, exception);
        } finally {
            /*
             * BoundedToolCallingManager 用 ThreadLocal 计数 Agent 轮次。Tomcat 线程池会复用线程，
             * 如果不在请求结束后清理，上一个请求的计数会残留到下一个请求，导致新请求立即触发
             * AgentRoundLimitExceededException。只有经过 .tools().call() 才会触发 ToolCallingManager，
             * 因此只需在此 gateway 清理。
             */
            if (toolCallingManager != null) {
                toolCallingManager.clearRequest();
            }
            ToolCallScope.clear();
            if (runBudgetTracker != null) {
                runBudgetTracker.finish(toolRunId);
            }
            if (trajectoryRecorder != null) {
                trajectoryRecorder.finishRun(toolRunId, "SUCCESS",
                        System.currentTimeMillis() - toolRunStart, null);
            }
        }
    }

    /** 把工具 Bean 转为回调并统一施加治理（预算/超时/审计）。 */
    private ToolCallback[] governedToolCallbacks(Object[] tools) {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);
        for (int i = 0; i < callbacks.length; i++) {
            callbacks[i] = new GovernedToolCallback(
                    callbacks[i], runBudgetTracker, trajectoryRecorder, toolTimeoutExecutor);
        }
        return callbacks;
    }

    /**
     * 解析当前可用的工具列表：
     * <ul>
     *   <li>生产环境：按本轮用户消息意图裁剪工具子集（{@link #toolsForPrompt}），
     *       降低"38 个工具"决策负担导致的漏调/错调与首轮耗时</li>
     *   <li>测试环境：使用构造函数传入的 {@code testToolBeans}</li>
     * </ul>
     */
    private Object[] resolveTools(String prompt) {
        if (toolRegistry != null) {
            return toolRegistry.toolBeansForGroups(toolsForPrompt(prompt));
        }
        return testToolBeans;
    }

    /**
     * 按用户消息意图选择工具分组：核心组始终提供；推荐轮附带试穿组（推荐后随时衔接试穿），
     * 衣橱/试穿轮提供衣橱查看与试穿组，入库轮追加抠图入库组，提醒轮提供提醒组。
     * 路由前先剥离 {@code [内部...[/内部...]} 工作流上下文块，避免内部指令里的
     * "试穿/衣橱/衣服"等词误命中意图正则（生产 prompt 会被 FashionAgentWorkflowContextProvider 追加上下文）。
     */
    static Set<String> toolsForPrompt(String prompt) {
        Set<String> groups = new HashSet<>();
        groups.add(ToolRegistry.GROUP_CORE);
        String userMessage = stripInternalContext(prompt);
        if (userMessage == null || userMessage.isBlank()) {
            return groups;
        }
        if (hasFashionConsultIntent(userMessage)) {
            groups.add(ToolRegistry.GROUP_TRYON);
        }
        if (hasTryOnIntent(userMessage)) {
            groups.add(ToolRegistry.GROUP_TRYON);
            groups.add(ToolRegistry.GROUP_WARDROBE_VIEW);
        }
        if (INTENT_WARDROBE_VIEW.matcher(userMessage).find()) {
            groups.add(ToolRegistry.GROUP_WARDROBE_VIEW);
            groups.add(ToolRegistry.GROUP_TRYON);
        }
        if (INTENT_FASHION_PROFILE.matcher(userMessage).find()) {
            groups.add(ToolRegistry.GROUP_WARDROBE_VIEW);
        }
        if (INTENT_WARDROBE_INTAKE.matcher(userMessage).find()) {
            // 诊断：prompt 含内部上下文但剥离后仍命中入库意图——说明剥离可能有残留，需定位根因
            if (prompt != null && prompt.contains("[内部")) {
                log.info("ROUTE-DIAG: intake matched while internal blocks present, stripped=[{}]", userMessage);
            }
            groups.add(ToolRegistry.GROUP_WARDROBE_INTAKE);
            groups.add(ToolRegistry.GROUP_WARDROBE_VIEW);
            groups.add(ToolRegistry.GROUP_TRYON);
        }
        if (INTENT_REMINDER.matcher(userMessage).find()) {
            groups.add(ToolRegistry.GROUP_REMINDER);
        }
        return groups;
    }

    /**
     * 剥离工作流上下文块（形如 {@code [内部最近穿搭推荐方案：...[/内部最近穿搭推荐方案]}）。
     * 生产 prompt = 用户消息 + 内部上下文；意图路由必须只看用户消息本身。
     */
    static String stripInternalContext(String prompt) {
        if (prompt == null || prompt.isBlank() || !prompt.contains("[内部")) {
            return prompt;
        }
        return prompt.replaceAll("\\[内部[^\\]]*\\][\\s\\S]*?\\[/内部[^\\]]*\\]", "").strip();
    }

    Prompt buildPrompt(List<ConversationMessage> history, String prompt) {
        return buildPrompt(history, prompt, properties.getMaxCompletionTokens());
    }

    Prompt buildPrompt(List<ConversationMessage> history, String prompt, int maxOutputTokens) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(resolveSystemPrompt(prompt)));
        for (ConversationMessage message : history == null ? List.<ConversationMessage>of() : history) {
            messages.add(message.role() == ConversationMessage.Role.USER
                    ? new UserMessage(message.text())
                    : new AssistantMessage(message.text()));
        }
        messages.add(new UserMessage(prompt));

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .maxCompletionTokens(Math.max(1, maxOutputTokens))
                .store(false)
                .build();
        return new Prompt(messages, options);
    }

    /**
     * 组装 system prompt：静态基础提示词 + 由 {@link ToolRegistry} 动态生成的能力声明，
     * 保证能力边界与工具注册结果一致，不随工具增删漂移。
     * 能力声明按本轮意图裁剪后的工具子集生成，避免"声明 38 个工具、实际只提供子集"误导模型。
     */
    private String resolveSystemPrompt(String prompt) {
        String base = properties.getSystemPrompt();
        if (toolRegistry == null) {
            return base;
        }
        List<String> names = toolRegistry.toolNamesForGroups(toolsForPrompt(prompt));
        if (names.isEmpty()) {
            return base;
        }
        String declaration = "当前可用的工具：" + String.join("、", names)
                + "。本轮只能调用这些工具；除此之外没有文档、语音、飞书、娱乐或通用信息查询能力，不要假装可以完成。";
        return base + "\n" + declaration;
    }

    /**
     * 模型漏调 {@code fashion_consultant} 时的兜底补调。
     *
     * <p>用户明确要求穿搭推荐（如"推荐一套适合今天在杭州打羽毛球的穿搭"），但模型
     * 先查询天气后直接输出穿搭文字、未调用推荐工具（LLM 工具调用不稳定），导致回复
     * 没有参考图。此时自动补调一次 fashion_consultant，把带参考图的方案追加到回复。</p>
     */
    private String maybeAutoConsult(String userId, String prompt, String text) {
        if (toolCallingManager == null || toolRegistry == null) {
            return text;
        }
        Set<String> called = toolCallingManager.calledToolNames();
        if (called.contains(FASHION_CONSULT_TOOL)
                || called.stream().anyMatch(FASHION_DOMAIN_TOOLS::contains)) {
            return text;
        }
        if (!hasFashionConsultIntent(stripInternalContext(prompt))) {
            return text;
        }
        Optional<ToolRegistry.ToolEntry> entry = toolRegistry.find(FASHION_CONSULT_TOOL);
        if (entry.isEmpty()) {
            return text;
        }
        try {
            log.info("Auto-invoking {} for user {}: model replied without calling it", FASHION_CONSULT_TOOL, userId);
            Object result = entry.get().method().invoke(entry.get().bean(), prompt);
            if (result != null && !result.toString().isBlank()) {
                return text + "\n\n" + result;
            }
        } catch (Exception failure) {
            log.warn("Auto {} fallback failed: {}", FASHION_CONSULT_TOOL, failure.getMessage());
        }
        return text;
    }

    /** 本轮用户输入是否包含明确的穿搭推荐意图（同时排除衣橱/试穿/图片操作消息）。 */
    static boolean hasFashionConsultIntent(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        if (FASHION_CONSULT_EXCLUDE.matcher(prompt).find()) {
            return false;
        }
        return FASHION_CONSULT_INTENT.matcher(prompt).find();
    }

    /**
     * 试穿漏调兜底：模型对"试穿/试试"类请求只回复承诺文案、未调用试穿工具时，
     * 自动补调一次 {@code virtual_try_on_reference_outfit}（取最近一次推荐编号整套试穿），
     * 把后台任务提示追加到回复，避免用户等不到效果图。
     */
    private String maybeAutoTryOn(String userId, String prompt, String text) {
        if (toolCallingManager == null || toolRegistry == null || fashionConversationService == null) {
            return text;
        }
        String stripped = stripInternalContext(prompt);
        Set<String> called = toolCallingManager.calledToolNames();
        // 已提交过试穿或本轮正在换推荐（consult）时不再补调；仅查询衣橱（search_wardrobe 等）
        // 不算提交试穿，模型可能查完单品却漏调试穿工具（8.28），仍需兜底。
        if (called.contains(TRY_ON_TOOL) || called.contains("virtual_try_on_wardrobe_item")
                || called.contains("fashion_consultant")) {
            return text;
        }
        if (!hasTryOnIntent(stripped)) {
            return text;
        }
        // 衣橱单品试穿（如"试穿这件白色T恤"）：模型只查了单品未调试穿工具时，
        // 用语义搜索定位 wardrobeItemId 并补调 virtual_try_on_wardrobe_item。
        if (hasWardrobeItemIntent(stripped)) {
            return maybeAutoWardrobeItemTryOn(userId, stripped, text, called);
        }
        String outfitId = fashionConversationService.findLatestReferenceOutfit(userId);
        if (outfitId == null || outfitId.isBlank()) {
            return text;
        }
        Optional<ToolRegistry.ToolEntry> entry = toolRegistry.find(TRY_ON_TOOL);
        if (entry.isEmpty()) {
            return text;
        }
        try {
            log.info("Auto-invoking {} for user {}: model replied without calling it, outfit={}",
                    TRY_ON_TOOL, userId, outfitId);
            Object result = entry.get().method().invoke(entry.get().bean(), outfitId, null);
            if (result != null && !result.toString().isBlank()) {
                return text + "\n\n" + result;
            }
        } catch (Exception failure) {
            log.warn("Auto {} fallback failed: {}", TRY_ON_TOOL, failure.getMessage());
        }
        return text;
    }

    /** 语义搜索结果中的内部 wardrobeItemId（如 "- wardrobeItemId=7；名称=白色T恤"）。 */
    private static final Pattern WARDROBE_ITEM_ID_PATTERN = Pattern.compile("wardrobeItemId=(\\d+)");

    /** 衣橱单品试穿漏调兜底：用户明确指向衣橱单品（"试穿这件白色T恤"），模型只调了
     *  search_wardrobe 查询、未调 virtual_try_on_wardrobe_item 时，按描述语义搜索定位
     *  wardrobeItemId 并补调，保证用户能收到上身效果图。 */
    private String maybeAutoWardrobeItemTryOn(
            String userId, String stripped, String text, Set<String> called
    ) {
        if (called.contains("virtual_try_on_reference_outfit")) {
            return text; // 已提交过试穿（哪怕是错调的推荐方案），不再重复补调
        }
        try {
            Optional<ToolRegistry.ToolEntry> search = toolRegistry.find("search_wardrobe_semantic");
            if (search.isEmpty()) {
                return text;
            }
            String query = extractGarmentDescription(stripped);
            Object searchResult = search.get().method().invoke(search.get().bean(),
                    query, null, null, null, null, null, null, null, null, null);
            if (searchResult == null || searchResult.toString().isBlank()) {
                return text;
            }
            Matcher idMatcher = WARDROBE_ITEM_ID_PATTERN.matcher(searchResult.toString());
            if (!idMatcher.find()) {
                return text;
            }
            long wardrobeItemId = Long.parseLong(idMatcher.group(1));
            Optional<ToolRegistry.ToolEntry> tryOn = toolRegistry.find("virtual_try_on_wardrobe_item");
            if (tryOn.isEmpty()) {
                return text;
            }
            log.info("Auto-invoking virtual_try_on_wardrobe_item for user {}: model replied without calling it, wardrobeItemId={}",
                    userId, wardrobeItemId);
            Object result = tryOn.get().method().invoke(tryOn.get().bean(), wardrobeItemId);
            if (result != null && !result.toString().isBlank()) {
                return text + "\n\n" + result;
            }
        } catch (Exception failure) {
            log.warn("Auto wardrobe-item try-on fallback failed: {}", failure.getMessage());
        }
        return text;
    }

    /** 从试穿意图文本中剥离动作词，保留单品描述（"试穿这件白色T恤" → "白色T恤"）。 */
    private static final Pattern TRY_ON_ACTION_WORDS = Pattern.compile(
            "(?<!面)试穿|穿一下|穿穿|上身效果|穿上看看|试试|试一下|试一试|试下|换上|换一下|换这身|换这一身"
                    + "|这件|那件|这套|那套|这个|那个|一下|看看|帮我把|帮我");

    private static String extractGarmentDescription(String prompt) {
        String cleaned = TRY_ON_ACTION_WORDS.matcher(prompt).replaceAll(" ").strip();
        return cleaned.isBlank() ? prompt : cleaned;
    }

    /** 本轮用户输入是否包含明确的试穿执行意图（排除衣橱单品/疑问/图片类消息）。 */
    static boolean hasTryOnIntent(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        if (TRY_ON_EXCLUDE.matcher(prompt).find()) {
            return false;
        }
        return TRY_ON_INTENT.matcher(prompt).find();
    }

    /**
     * 衣橱单品错调兜底：用户明确指向衣橱具体单品（如"试一下灰色T恤"），
     * 但模型误调用了 {@code virtual_try_on_reference_outfit}（装的是参考推荐库的衣服）时，
     * 追加纠正提示，避免用户收到穿错衣服的效果图却不知道原因。
     */
    private String maybeCorrectWardrobeMisroute(String userId, String prompt, String text) {
        if (toolCallingManager == null || !hasWardrobeItemIntent(stripInternalContext(prompt))) {
            return text;
        }
        Set<String> called = toolCallingManager.calledToolNames();
        boolean misrouted = called.contains("virtual_try_on_reference_outfit")
                && !called.contains("virtual_try_on_wardrobe_item");
        if (!misrouted) {
            return text;
        }
        log.info("Wardrobe-item intent for user {} routed to reference try-on, appending correction", userId);
        return text + "\n\n（提醒：你提到的是衣橱里的具体单品，刚才那套是参考推荐库的衣服。"
                + "请再说一次如\"试穿灰色T恤\"，我会直接试穿衣橱里的那件。）";
    }

    /** 是否指向衣橱具体单品：试穿类表达 + 衣橱单品词（T恤/衬衫/颜色/那件等）。 */
    static boolean hasWardrobeItemIntent(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        return hasTryOnIntent(prompt) && WARDROBE_ITEM_WORDS.matcher(prompt).find();
    }

    /**
     * 照片入库漏调兜底：用户明确要加入衣橱/抠图（且刚发过照片），但模型只回复承诺文案
     * （如"正在为你提取这套穿搭"）未调用任何入库工具时，自动补调一次
     * {@code analyze_wardrobe_photo}（取最近上传照片），把识别任务真正提交后台，
     * 避免用户永远等不到候选和抠图。
     */
    private String maybeAutoWardrobeIntake(String userId, String prompt, String text) {
        if (toolCallingManager == null || toolRegistry == null || imageStore == null) {
            return text;
        }
        if (!shouldAutoWardrobeIntake(toolCallingManager.calledToolNames(), prompt)) {
            return text;
        }
        // 诊断：兜底触发时的剥离文本与已调工具，用于定位"试穿一下"被误判入库的残留词来源
        log.info("AUTO-INTAKE-DIAG: user={} called={} strippedPrompt=[{}]",
                userId, toolCallingManager.calledToolNames(), stripInternalContext(prompt));
        String imageAssetId = imageStore.recent(userId, 1).stream()
                .findFirst()
                .map(com.wechatbot.fashion.ai.service.LocalImageAssetStore.StoredImage::assetId)
                .orElse(null);
        if (imageAssetId == null || imageAssetId.isBlank()) {
            return text;
        }
        // 该照片是否已有"待选候选"（PENDING_SELECTION 且 READY）：有 → 用户"确认/加入"是
        // 确认候选，应提交抠图而非重新识别；无 → 需要先识别出候选。避免"确认"轮兜底错调
        // analyze 把内部候选列表外泄。抠图完成后的"确认"（AWAITING_FINAL_CONFIRMATION）不在此列，
        // 模型通常能正常调 confirm_wardrobe_candidate，不走兜底。
        boolean hasCandidates = false;
        if (wardrobeIngestion != null) {
            try {
                hasCandidates = wardrobeIngestion.candidatesForPhoto(userId, imageAssetId, null).stream()
                        .anyMatch(candidate -> candidate.status()
                                        == com.wechatbot.fashion.wardrobe.domain.ClothingCandidateStatus.PENDING_SELECTION
                                && candidate.completenessStatus()
                                        == com.wechatbot.fashion.wardrobe.domain.ClothingCompletenessStatus.READY);
            } catch (RuntimeException ignored) {
                // 图片不可用等异常视为无候选，兜底走识别。
            }
        }
        String toolName = hasCandidates ? "submit_garment_cutout" : "analyze_wardrobe_photo";
        Optional<ToolRegistry.ToolEntry> entry = toolRegistry.find(toolName);
        if (entry.isEmpty()) {
            return text;
        }
        try {
            Object result;
            if (hasCandidates) {
                // candidateIds 留空：submit_garment_cutout 会按 imageAssetId 把候选限定到该照片
                log.info("Auto-invoking submit_garment_cutout for user {}: model replied without calling it, image={}",
                        userId, imageAssetId);
                result = entry.get().method().invoke(entry.get().bean(), null, imageAssetId);
            } else {
                log.info("Auto-invoking analyze_wardrobe_photo for user {}: model replied without calling it, image={}",
                        userId, imageAssetId);
                result = entry.get().method().invoke(entry.get().bean(), imageAssetId, null);
            }
            if (result == null || result.toString().isBlank()) {
                return text;
            }
            String fallback = sanitizeWardrobeToolResult(result.toString());
            // 模型错误地把用户刚发照片当"参考款/推荐款"拒绝入库时，用兜底提示替换拒绝文案，
            // 避免用户看到互相矛盾的两段话；正常漏调则追加。
            boolean modelRefused = text.contains("无法入库") || text.contains("不能加入")
                    || text.contains("没办法加入") || text.contains("无法加入")
                    || text.contains("参考款") || text.contains("推荐款");
            return modelRefused ? fallback : text + "\n\n" + fallback;
        } catch (Exception failure) {
            log.warn("Auto {} fallback failed: {}", toolName, failure.getMessage());
        }
        return text;
    }

    /** 兜底工具结果清洗：剥离"内部候选"等仅供模型使用的结构，防止候选编号等内部信息外泄给用户。 */
    private static String sanitizeWardrobeToolResult(String result) {
        if (result == null) {
            return "";
        }
        String clean = result;
        int internal = clean.indexOf("内部候选");
        if (internal >= 0) {
            clean = clean.substring(0, internal).strip();
            if (clean.isBlank()) {
                clean = "正在识别图片中，识别完成后会自动把候选和完整度发给你。";
            }
        }
        return clean;
    }

    /**
     * 是否需要照片入库兜底：本轮未调用任何穿搭域工具，且用户输入（剥离内部上下文后）
     * 命中入库/抠图意图。模型已调过入库工具说明链路正在进行，不再重复触发。
     */
    static boolean shouldAutoWardrobeIntake(Set<String> called, String prompt) {
        if (called != null && called.stream().anyMatch(FASHION_DOMAIN_TOOLS::contains)) {
            return false;
        }
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String stripped = stripInternalContext(prompt);
        // 纯试穿执行意图（如"试穿一下"）绝不触发照片入库兜底，避免把试穿误判成抠图入库；
        // 即使内部上下文剥离异常残留了"确认/照片"等词，只要用户本意是试穿就不该走入库链路。
        // 注意用 TRY_ON_INTENT 而非 hasTryOnIntent：后者带"照片/图片"排除词，剥离残留会误判。
        if (TRY_ON_INTENT.matcher(stripped).find()) {
            return false;
        }
        return INTENT_WARDROBE_INTAKE.matcher(stripped).find();
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text.strip();
    }

    /** 响应是否为工具调用轮（模型只回了 tool_calls、没有文本内容）。 */
    private static boolean hasToolCalls(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return false;
        }
        if (response.getResult().getOutput() instanceof AssistantMessage assistant) {
            List<?> toolCalls = assistant.getToolCalls();
            return toolCalls != null && !toolCalls.isEmpty();
        }
        return false;
    }

    private static String finishReason(ChatResponse response) {
        // Spring AI 1.1.8 的 ChatResponseMetadata 未暴露 finishReason；诊断以 hasToolCalls + usage 为主。
        return "n/a";
    }

    private AiModelUsage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return AiModelUsage.unknown();
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return AiModelUsage.unknown();
        }
        return AiModelUsage.reported(
                nonNegative(usage.getPromptTokens()),
                nonNegative(usage.getCompletionTokens()),
                nonNegative(usage.getTotalTokens())
        );
    }

    private int outputLimit(AiRequestBudget budget) {
        return budget == null ? properties.getMaxCompletionTokens() : budget.maxOutputTokens();
    }

    private static long nonNegative(Integer value) {
        return value == null ? 0L : Math.max(0L, value.longValue());
    }

    private static boolean isAuthenticationFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof RestClientResponseException responseException) {
                HttpStatusCode status = responseException.getStatusCode();
                if (status.value() == 401 || status.value() == 403) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
