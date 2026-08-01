package com.example.ykdsummer.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.fashion.application.FashionAgentWorkflowContextProvider;
import com.example.ykdsummer.fashion.application.FashionWardrobeDraftCommandHandler;
import com.example.ykdsummer.fashion.tool.FashionWardrobeVisualCommandHandler;
import com.example.ykdsummer.persistence.ConversationHistoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理每个微信用户的内存对话，并把普通问题交给模型网关。
 *
 * <p>本类不直接发送 HTTP，也不知道微信如何长轮询。它接收
 * {@link com.example.ykdsummer.bot.service.ILinkReplyService} 整理好的用户 ID、文字和图片，
 * 找到该用户自己的历史记录，然后调用 {@link LlmGateway}。</p>
 *
 * <p>历史记录只存在当前 Java 进程内存中，以 iLink 的 {@code fromUserId} 为键。两个微信
 * 用户使用不同的键，因此聊天不会混在一起；应用重启后这些记录会全部消失。</p>
 */
@Service
public class AiChatService {

    public static final String DISABLED_REPLY = "AI 功能暂未启用";
    public static final String AUTH_ERROR_REPLY = "AI 服务认证失败，请联系管理员";
    public static final String UNAVAILABLE_REPLY = "AI 暂时没有响应，请稍后重试";
    public static final String EMPTY_REPLY = "暂时没有生成有效回答";
    public static final String AGENT_ROUND_LIMIT_REPLY = "这个任务连续调用工具次数较多，已停止继续执行。你可以把需求拆得更具体一些再试。";

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AiProperties properties;
    private final LlmGateway gateway;
    private final AiTraceLogger trace;
    private final AiUsageMeter usageMeter;
    private final TokenBudgetPolicy budgetPolicy;
    private volatile ConversationHistoryStore conversationHistory = ConversationHistoryStore.disabled();
    private volatile FashionAgentWorkflowContextProvider fashionWorkflowContext;
    private volatile FashionWardrobeDraftCommandHandler wardrobeDraftCommands;
    private volatile FashionWardrobeVisualCommandHandler wardrobeVisualCommands;
    /** key 是 iLink fromUserId，value 是该微信用户自己的最近对话。 */
    private final Cache<String, UserConversation> conversations;

    public AiChatService(AiProperties properties, LlmGateway gateway) {
        this(properties, gateway, AiTraceLogger.disabled(), AiUsageMeter.disabled(),
                new TokenBudgetPolicy(new com.example.ykdsummer.ai.config.AiUsageProperties()));
    }

    public AiChatService(AiProperties properties, LlmGateway gateway, AiTraceLogger trace) {
        this(properties, gateway, trace, AiUsageMeter.disabled(),
                new TokenBudgetPolicy(new com.example.ykdsummer.ai.config.AiUsageProperties()));
    }

    @Autowired
    public AiChatService(
            AiProperties properties,
            LlmGateway gateway,
            AiTraceLogger trace,
            AiUsageMeter usageMeter,
            TokenBudgetPolicy budgetPolicy
    ) {
        this.properties = properties;
        this.gateway = gateway;
        this.trace = trace;
        this.usageMeter = usageMeter;
        this.budgetPolicy = budgetPolicy;
        this.conversations = Caffeine.newBuilder()
                .maximumSize(properties.getMaxMemoryUsers())
                .expireAfterAccess(safeMemoryTimeout(properties.getMemoryIdleTimeout()))
                .build();
    }

    @Autowired(required = false)
    void setConversationHistory(ConversationHistoryStore conversationHistory) {
        this.conversationHistory = conversationHistory == null ? ConversationHistoryStore.disabled() : conversationHistory;
    }

    /**
     * A narrow business guard for an explicit edit to the user's sole pending garment draft. It prevents a model
     * response from claiming a revision was submitted without the durable fashion task actually being created.
     */
    @Autowired(required = false)
    void setWardrobeDraftCommands(FashionWardrobeDraftCommandHandler wardrobeDraftCommands) {
        this.wardrobeDraftCommands = wardrobeDraftCommands;
    }

    @Autowired(required = false)
    void setFashionWorkflowContext(FashionAgentWorkflowContextProvider fashionWorkflowContext) {
        this.fashionWorkflowContext = fashionWorkflowContext;
    }

    @Autowired(required = false)
    void setWardrobeVisualCommands(FashionWardrobeVisualCommandHandler wardrobeVisualCommands) {
        this.wardrobeVisualCommands = wardrobeVisualCommands;
    }

    /**
     * 回答一条普通问题。调用者是 ILinkReplyService，返回值最终仍会作为文字发回微信。
     *
     * @param userId 入站 WeixinMessage.fromUserId()，用于隔离不同用户的历史
     * @param prompt 当前消息中的文字，或微信已提供的语音转写
     * @param images 本轮入站图片的解密字节；没有图片时是空列表
     */
    public String answer(String userId, String prompt, List<AiImage> images) {
        return answer(userId, prompt, images, List.of());
    }

    /** 文件和图片都只用于当前轮次，不把二进制内容放入聊天记忆。 */
    public String answer(String userId, String prompt, List<AiImage> images, List<AiFile> files) {
        return answerRich(userId, prompt, images, files).text();
    }

    /** 供微信回复层使用：除文字外还能带回本轮 Agent 工具生成的图片。 */
    public AssistantAnswer answerRich(String userId, String prompt, List<AiImage> images, List<AiFile> files) {
        return answerInternal(userId, prompt, prompt, images, files);
    }

    /**
     * “语音：”模式的回答入口。模型额外收到简洁播报要求，但聊天记录只保存用户真实问题，
     * 不把内部的长度限制带进下一轮对话。
     */
    public String answerForVoice(String userId, String prompt) {
        String modelPrompt = prompt + "\n\n请用自然、适合语音播报的简洁中文回答，最多 120 个汉字。";
        return answerInternal(userId, prompt, modelPrompt, List.of(), List.of()).text();
    }

    /**
     * 允许业务层附加用户不可见的内部协议提示，同时聊天记忆只保存用户真正发送的文字。
     * TJY 文件生成协议使用这个入口让模型返回 FILE_GEN||JSON 标记。
     */
    public String answerWithInternalPrompt(
            String userId,
            String userPrompt,
            String modelPrompt,
            List<AiFile> files
    ) {
        return answerInternal(userId, userPrompt, modelPrompt, List.of(), files == null ? List.of() : files).text();
    }

    public AssistantAnswer answerWithInternalPromptRich(
            String userId, String userPrompt, String modelPrompt, List<AiFile> files
    ) {
        return answerInternal(userId, userPrompt, modelPrompt, List.of(), files == null ? List.of() : files);
    }

    private AssistantAnswer answerInternal(
            String userId,
            String memoryPrompt,
            String modelPrompt,
            List<AiImage> images,
            List<AiFile> files
    ) {
        if (!properties.isEnabled()) {
            return AssistantAnswer.text(DISABLED_REPLY);
        }
        // Caffeine 按访问时间自动过期，并对总用户数设置上限，避免长期运行后 Map 无限增长。
        UserConversation conversation = conversations.get(userId,
                ignored -> new UserConversation(conversationHistory.load(ignored, properties.getMaxMemoryMessages())));
        /*
         * Caffeine 负责会话对象的容量和过期，不负责同一用户两次请求的历史顺序。
         * synchronized 锁住该用户自己的会话：同一用户必须一个问题回答完再记下一条；
         * 不同用户锁的是不同对象，仍然可以并行。
         */
        synchronized (conversation) {
            try {
                List<ConversationMessage> history = conversation.copyMessages();
                if ((images == null || images.isEmpty()) && (files == null || files.isEmpty())) {
                    String revisionReply = handleExplicitWardrobeRevision(userId, memoryPrompt);
                    if (revisionReply != null) {
                        rememberLocalTurn(conversation, userId, memoryPrompt, revisionReply, images, files);
                        return AssistantAnswer.text(revisionReply);
                    }
                    AssistantAnswer visualReply = handleExplicitWardrobeVisualRequest(userId, memoryPrompt, history);
                    if (visualReply != null) {
                        rememberLocalTurn(conversation, userId, memoryPrompt, visualReply.text(), images, files);
                        return visualReply;
                    }
                }
                String effectiveModelPrompt = withFashionWorkflowContext(userId, modelPrompt);
                /*
                 * Completion 与 Responses 请求都设置为 store=false，服务端不替我们保存上下文。
                 * 所以每次调用都复制最近历史，并连同本轮 prompt/images 重新发给模型。
                 */
                trace.request(userId, memoryPrompt, effectiveModelPrompt, history.size(), images, files);
                AiRequestBudget budget = budgetPolicy.plan(history, effectiveModelPrompt, images, files);
                AiUsageMeter.Reservation reservation = usageMeter.reserve(userId, budget);
                if (!reservation.allowed()) {
                    log.info("AI request rejected by budget, user={}, reason={}, taskClass={}",
                            anonymize(userId), reservation.rejectReason(), budget.taskClass());
                    return AssistantAnswer.text(reservation.rejectReason() == AiUsageMeter.RejectReason.INPUT_TOO_LARGE
                            ? AiUsageMeter.INPUT_TOO_LARGE_REPLY
                            : AiUsageMeter.DAILY_LIMIT_REPLY);
                }
                LlmGateway.ModelReply reply;
                boolean settled = false;
                long gatewayStartedAt = System.nanoTime();
                try {
                    reply = gateway.generate(userId, history, effectiveModelPrompt, images, files, budget);
                    long durationMs = elapsedMillis(gatewayStartedAt);
                    usageMeter.complete(reservation, reply.protocol(), reply.model(), reply.usage(), durationMs);
                    trace.modelCompleted(userId, reply.protocol(), reply.model(), durationMs, reply.usage(), reply.text());
                    settled = true;
                } catch (AiGatewayException exception) {
                    long durationMs = elapsedMillis(gatewayStartedAt);
                    String protocol = anticipatedProtocol(images, files);
                    usageMeter.fail(reservation, protocol, "", exception.kind().name(), durationMs);
                    trace.modelFailure(userId, protocol, "", durationMs, exception.kind().name());
                    throw exception;
                } catch (RuntimeException exception) {
                    long durationMs = elapsedMillis(gatewayStartedAt);
                    String protocol = anticipatedProtocol(images, files);
                    usageMeter.fail(reservation, protocol, "", "UNEXPECTED_ERROR", durationMs);
                    trace.modelFailure(userId, protocol, "", durationMs, "UNEXPECTED_ERROR");
                    throw exception;
                } finally {
                    if (!settled) {
                        usageMeter.release(reservation);
                    }
                }
                // 只有模型成功返回后才把这一问一答写入历史，失败提示不会污染下一轮上下文。
                rememberLocalTurn(conversation, userId, memoryPrompt, reply.text(), images, files);
                return new AssistantAnswer(reply.text(), reply.artifacts());
            } catch (AiGatewayException exception) {
                // AiTraceLogger already emitted the protocol, duration and sanitized user label for this failure.
                log.debug("AI request mapped to user-safe reply, user={}, kind={}", anonymize(userId), exception.kind());
                String fallback = handleWardrobeCommandFallback(userId, memoryPrompt, images, files);
                if (fallback != null) {
                    rememberLocalTurn(conversation, userId, memoryPrompt, fallback, images, files);
                    return AssistantAnswer.text(fallback);
                }
                return AssistantAnswer.text(switch (exception.kind()) {
                    case AUTHENTICATION -> AUTH_ERROR_REPLY;
                    case EMPTY_RESPONSE -> EMPTY_REPLY;
                    case TEMPORARY_UNAVAILABLE -> UNAVAILABLE_REPLY;
                    case AGENT_ROUND_LIMIT -> AGENT_ROUND_LIMIT_REPLY;
                });
            } catch (RuntimeException exception) {
                log.debug("Unexpected AI failure mapped to user-safe reply, user={}, type={}",
                        anonymize(userId), exception.getClass().getSimpleName());
                String fallback = handleWardrobeCommandFallback(userId, memoryPrompt, images, files);
                if (fallback != null) {
                    rememberLocalTurn(conversation, userId, memoryPrompt, fallback, images, files);
                    return AssistantAnswer.text(fallback);
                }
                return AssistantAnswer.text(UNAVAILABLE_REPLY);
            }
        }
    }

    public void clear(String userId) {
        // “清空”命令传入当前发送者 ID，只删除这一位用户的记录。
        UserConversation removed = conversations.getIfPresent(userId);
        conversations.invalidate(userId);
        if (removed != null) {
            synchronized (removed) {
                removed.messages.clear();
            }
        }
        conversationHistory.clear(userId);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    private String handleWardrobeCommandFallback(
            String userId, String prompt, List<AiImage> images, List<AiFile> files
    ) {
        if ((images != null && !images.isEmpty()) || (files != null && !files.isEmpty())) return null;
        FashionWardrobeDraftCommandHandler handler = wardrobeDraftCommands;
        if (handler == null) return null;
        try {
            return handler.handle(userId, prompt).orElse(null);
        } catch (RuntimeException failure) {
            log.warn("Fashion wardrobe fallback could not be executed, user={}", anonymize(userId), failure);
            return null;
        }
    }

    private String handleExplicitWardrobeRevision(String userId, String prompt) {
        FashionWardrobeDraftCommandHandler handler = wardrobeDraftCommands;
        if (handler == null) return null;
        try {
            return handler.handleExplicitRevision(userId, prompt).orElse(null);
        } catch (RuntimeException failure) {
            log.warn("Explicit wardrobe draft revision could not be executed, user={}", anonymize(userId), failure);
            return null;
        }
    }

    private String withFashionWorkflowContext(String userId, String modelPrompt) {
        FashionAgentWorkflowContextProvider provider = fashionWorkflowContext;
        if (provider == null) return modelPrompt;
        try {
            String context = provider.contextFor(userId);
            return context == null || context.isBlank() ? modelPrompt : modelPrompt + context;
        } catch (RuntimeException failure) {
            log.warn("Fashion workflow context could not be loaded, user={}", anonymize(userId), failure);
            return modelPrompt;
        }
    }

    private AssistantAnswer handleExplicitWardrobeVisualRequest(
            String userId, String prompt, List<ConversationMessage> history
    ) {
        FashionWardrobeVisualCommandHandler handler = wardrobeVisualCommands;
        if (handler == null) return null;
        try {
            return handler.handle(userId, prompt, history).orElse(null);
        } catch (RuntimeException failure) {
            log.warn("Explicit wardrobe image request could not be prepared, user={}", anonymize(userId), failure);
            return null;
        }
    }

    private void rememberLocalTurn(
            UserConversation conversation, String userId, String memoryPrompt, String reply,
            List<AiImage> images, List<AiFile> files
    ) {
        ConversationMessage userMessage = new ConversationMessage(
                ConversationMessage.Role.USER, memoryText(memoryPrompt, images, files));
        ConversationMessage assistantMessage = new ConversationMessage(ConversationMessage.Role.ASSISTANT, reply);
        conversation.remember(userMessage, properties.getMaxMemoryMessages());
        conversation.remember(assistantMessage, properties.getMaxMemoryMessages());
        conversationHistory.appendTurn(userId, userMessage, assistantMessage);
    }

    public String model() {
        return properties.getModel();
    }

    int conversationCount() {
        conversations.cleanUp();
        return Math.toIntExact(conversations.estimatedSize());
    }

    /**
     * 图片只服务于当前模型请求，不把 Base64 大数据放进历史；历史中只留下“本轮带图”的文字提示。
     */
    private static String memoryText(String prompt, List<AiImage> images, List<AiFile> files) {
        int imageCount = images == null ? 0 : images.size();
        List<AiFile> safeFiles = files == null ? List.of() : files;
        StringBuilder memory = new StringBuilder(prompt);
        if (imageCount > 0) {
            memory.append("\n[本轮附带了 ").append(imageCount).append(" 张图片]");
        }
        if (!safeFiles.isEmpty()) {
            memory.append("\n[本轮附带文件：")
                    .append(safeFiles.stream().map(AiFile::fileName).collect(java.util.stream.Collectors.joining("、")))
                    .append(']');
        }
        return memory.toString();
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    private static String anticipatedProtocol(List<AiImage> images, List<AiFile> files) {
        return files != null && !files.isEmpty() ? "responses" : "chat-completions";
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static Duration safeMemoryTimeout(Duration configured) {
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofHours(2)
                : configured;
    }

    private static final class UserConversation {
        /** USER 和 ASSISTANT 消息交替存放，条数达到上限时从最旧消息开始删除。 */
        private final List<ConversationMessage> messages;

        private UserConversation(List<ConversationMessage> initialMessages) {
            this.messages = new ArrayList<>(initialMessages == null ? List.of() : initialMessages);
        }

        private List<ConversationMessage> copyMessages() {
            return List.copyOf(messages);
        }

        private void remember(ConversationMessage message, int maxMessages) {
            messages.add(message);
            // maxMessages 统计的是消息条数，不是问答轮数；默认 20 条约等于 10 轮。
            while (messages.size() > maxMessages) {
                messages.removeFirst();
            }
        }

    }

    public record AssistantAnswer(String text, List<AiArtifact> artifacts) {
        public AssistantAnswer {
            text = text == null ? "" : text;
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }

        public static AssistantAnswer text(String value) {
            return new AssistantAnswer(value, List.of());
        }
    }
}
