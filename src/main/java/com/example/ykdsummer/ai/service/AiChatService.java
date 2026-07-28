package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.schedule.model.ScheduledTask;
import com.example.ykdsummer.schedule.model.TaskStatus;
import com.example.ykdsummer.schedule.repo.ScheduledTaskRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.storage.db.SqliteChatMemory;
import com.google.common.util.concurrent.Striped;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import com.example.ykdsummer.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


/**
 * 管理每个微信用户的内存对话，并把普通问题交给模型网关。
 *
 * <p>本类不直接发送 HTTP，也不知道微信如何长轮询。它接收
 * {@link com.example.ykdsummer.bot.service.ILinkReplyService} 整理好的用户 ID、文字和图片，
 * 找到该用户自己的历史记录，然后调用 {@link LlmGateway}。</p>
 *
 * <p>历史记录通过 Caffeine 本地缓存或 SQLite 持久化存储，以 iLink 的 {@code fromUserId} 为键。
 * 两个微信用户使用不同的键，因此聊天不会混在一起。</p>
 */
@Service
public class AiChatService {

    public static final String DISABLED_REPLY = "AI 功能暂未启用";
    public static final String AUTH_ERROR_REPLY = "AI 服务认证失败，请联系管理员";
    public static final String UNAVAILABLE_REPLY = "AI 暂时没有响应，请稍后重试";
    public static final String EMPTY_REPLY = "暂时没有生成有效回答";

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AiProperties properties;
    private final LlmGateway gateway;
    private final AiTraceLogger trace;
    private final AiUsageMeter usageMeter;
    private final TokenBudgetPolicy budgetPolicy;
    /** key 是 iLink fromUserId，value 是该微信用户自己的最近对话。 */
    private final Cache<String, UserConversation> conversations;
    private final SqliteChatMemory chatMemory;
    private final Striped<ReadWriteLock> userLocks;
    private final ScheduledTaskRepository taskRepository;

    public AiChatService(AiProperties properties, LlmGateway gateway) {
        this(properties, gateway, AiTraceLogger.disabled(), AiUsageMeter.disabled(),
                new TokenBudgetPolicy(new com.example.ykdsummer.ai.config.AiUsageProperties()),
                null, null);
    }

    public AiChatService(AiProperties properties, LlmGateway gateway, AiTraceLogger trace) {
        this(properties, gateway, trace, AiUsageMeter.disabled(),
                new TokenBudgetPolicy(new com.example.ykdsummer.ai.config.AiUsageProperties()),
                null, null);
    }

    // 5-arg backward compat for tests (no SqliteChatMemory, no ScheduledTaskRepository)
    public AiChatService(
            AiProperties properties,
            LlmGateway gateway,
            AiTraceLogger trace,
            AiUsageMeter usageMeter,
            TokenBudgetPolicy budgetPolicy
    ) {
        this(properties, gateway, trace, usageMeter, budgetPolicy, null, null);
    }

    // 6-arg backward compat for tests (no ScheduledTaskRepository)
    public AiChatService(
            AiProperties properties,
            LlmGateway gateway,
            AiTraceLogger trace,
            AiUsageMeter usageMeter,
            TokenBudgetPolicy budgetPolicy,
            SqliteChatMemory chatMemory
    ) {
        this(properties, gateway, trace, usageMeter, budgetPolicy, chatMemory, null);
    }

    @Autowired
    public AiChatService(
            AiProperties properties,
            LlmGateway gateway,
            AiTraceLogger trace,
            AiUsageMeter usageMeter,
            TokenBudgetPolicy budgetPolicy,
            @Autowired(required = false) SqliteChatMemory chatMemory,
            @Autowired(required = false) ScheduledTaskRepository taskRepository
    ) {
        this.properties = properties;
        this.gateway = gateway;
        this.trace = trace;
        this.usageMeter = usageMeter;
        this.budgetPolicy = budgetPolicy;
        this.chatMemory = chatMemory;
        this.taskRepository = taskRepository;
        if (chatMemory != null) {
            this.conversations = null;
            this.userLocks = Striped.lazyWeakReadWriteLock(1024);
        } else {
            this.conversations = Caffeine.newBuilder()
                    .maximumSize(properties.getMaxMemoryUsers())
                    .expireAfterAccess(safeMemoryTimeout(properties.getMemoryIdleTimeout()))
                    .build();
            this.userLocks = null;
        }
    }

    public String answer(String userId, String prompt, List<AiImage> images) {
        return answer(userId, prompt, images, List.of());
    }

    public String answer(String userId, String prompt, List<AiImage> images, List<AiFile> files) {
        return answerRich(userId, prompt, images, files).text();
    }

    public AssistantAnswer answerRich(String userId, String prompt, List<AiImage> images, List<AiFile> files) {
        return answerInternal(userId, prompt, prompt, images, files);
    }

    public String answerForVoice(String userId, String prompt) {
        String modelPrompt = prompt + "\n\n请用自然、适合语音播报的简洁中文回答，最多 120 个汉字。";
        return answerInternal(userId, prompt, modelPrompt, List.of(), List.of()).text();
    }

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

        // 追加待执行定时任务上下文，让 AI 自然感知即将触发的提醒
        String context = pendingTaskContext(userId);
        String enhancedPrompt = context.isEmpty() ? modelPrompt : modelPrompt + context;

        if (chatMemory != null) {
            return answerSqliteInternal(userId, memoryPrompt, enhancedPrompt, images, files);
        }
        // Caffeine 按访问时间自动过期，并对总用户数设置上限，避免长期运行后 Map 无限增长。
        UserConversation conversation = conversations.get(userId, ignored -> new UserConversation());
        synchronized (conversation) {
            return executeWithGateway(userId, memoryPrompt, enhancedPrompt, images, files,
                    conversation::copyMessages,
                    (userMsg, assistantMsg) -> {
                        conversation.remember(userMsg, properties.getMaxMemoryMessages());
                        conversation.remember(assistantMsg, properties.getMaxMemoryMessages());
                    });
        }
    }

    private AssistantAnswer answerSqliteInternal(
            String userId,
            String memoryPrompt,
            String modelPrompt,
            List<AiImage> images,
            List<AiFile> files
    ) {
        String conversationId = userId + "::single";
        Lock lock = userLocks.get(userId).writeLock();
        lock.lock();
        try {
            return executeWithGateway(userId, memoryPrompt, modelPrompt, images, files,
                    () -> {
                        int windowSize = chatMemory.getWindowSize(conversationId, properties.getMaxMemoryMessages());
                        return chatMemory.getAsConversationMessages(conversationId, windowSize);
                    },
                    (userMsg, assistantMsg) -> {
                        chatMemory.add(conversationId, List.of(toSpringMessage(userMsg)));
                        chatMemory.add(conversationId, List.of(toSpringMessage(assistantMsg)));
                    });
        } finally {
            lock.unlock();
        }
    }

    /**
     * 共享的网关调用模板方法。负责预算检查、用量计量、模型调用、历史保存和异常处理。
     *
     * @param historySupplier 获取历史记录列表
     * @param historySaver    保存用户与助理消息的回调
     */
    private AssistantAnswer executeWithGateway(
            String userId, String memoryPrompt, String modelPrompt,
            List<AiImage> images, List<AiFile> files,
            Supplier<List<ConversationMessage>> historySupplier,
            BiConsumer<ConversationMessage, ConversationMessage> historySaver
    ) {
        List<ConversationMessage> history;
        try {
            history = historySupplier.get();
        } catch (RuntimeException e) {
            log.warn("Failed to load chat history, user={}", anonymize(userId), e);
            return AssistantAnswer.text(UNAVAILABLE_REPLY);
        }

        trace.request(userId, memoryPrompt, modelPrompt, history.size(), images, files);
        AiRequestBudget budget = budgetPolicy.plan(history, modelPrompt, images, files);
        AiUsageMeter.Reservation reservation = usageMeter.reserve(userId, budget);
        if (!reservation.allowed()) {
            log.info("AI request rejected by budget, user={}, reason={}, taskClass={}",
                    anonymize(userId), reservation.rejectReason(), budget.taskClass());
            return AssistantAnswer.text(reservation.rejectReason() == AiUsageMeter.RejectReason.INPUT_TOO_LARGE
                    ? AiUsageMeter.INPUT_TOO_LARGE_REPLY
                    : AiUsageMeter.DAILY_LIMIT_REPLY);
        }

        try {
            LlmGateway.ModelReply reply = gateway.generate(userId, history, modelPrompt, images, files, budget);
            usageMeter.complete(reservation, reply.protocol(), reply.model(), reply.usage());

            ConversationMessage userMsg = new ConversationMessage(
                    ConversationMessage.Role.USER, memoryText(memoryPrompt, images, files));
            ConversationMessage assistantMsg = new ConversationMessage(
                    ConversationMessage.Role.ASSISTANT, reply.text());
            try {
                historySaver.accept(userMsg, assistantMsg);
            } catch (RuntimeException e) {
                log.warn("Failed to save chat history, user={}", anonymize(userId), e);
            }

            return new AssistantAnswer(reply.text(), reply.artifacts());
        } catch (AiGatewayException exception) {
            usageMeter.release(reservation);
            log.warn("AI request failed, user={}, kind={}", anonymize(userId), exception.kind());
            return AssistantAnswer.text(switch (exception.kind()) {
                case AUTHENTICATION -> AUTH_ERROR_REPLY;
                case EMPTY_RESPONSE -> EMPTY_REPLY;
                case TEMPORARY_UNAVAILABLE -> UNAVAILABLE_REPLY;
            });
        } catch (RuntimeException exception) {
            usageMeter.release(reservation);
            log.warn("Unexpected AI failure, user={}, type={}", anonymize(userId), exception.getClass().getSimpleName());
            return AssistantAnswer.text(UNAVAILABLE_REPLY);
        }
    }

    private static Message toSpringMessage(ConversationMessage msg) {
        return switch (msg.role()) {
            case USER -> new UserMessage(msg.text());
            case ASSISTANT -> new AssistantMessage(msg.text());
        };
    }

    public void clear(String userId) {
        if (chatMemory != null) {
            chatMemory.clear(userId + "::single");
            return;
        }
        UserConversation removed = conversations.getIfPresent(userId);
        conversations.invalidate(userId);
        if (removed != null) {
            synchronized (removed) {
                removed.messages.clear();
            }
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String model() {
        return properties.getModel();
    }

    int conversationCount() {
        if (chatMemory != null) {
            return 0;
        }
        conversations.cleanUp();
        return Math.toIntExact(conversations.estimatedSize());
    }

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

    /**
     * 查询用户待执行的定时任务，构建上下文提示附加到 modelPrompt 中。
     * 可以让 AI 在回复时自然提及即将触发的提醒，并知晓当前日期时间。
     */
    private String pendingTaskContext(String userId) {
        StringBuilder sb = new StringBuilder();

        // 当前时间上下文，方便 AI 创建提醒时自动补齐日期
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        sb.append("\n\n## 当前时间\n")
                .append(now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("（周").append(now.getDayOfWeek().getValue()).append("）\n");

        if (taskRepository == null || userId == null) {
            return sb.toString();
        }
        try {
            List<ScheduledTask> waiting = taskRepository.findByUserId(userId).stream()
                    .filter(t -> t.getStatus() == TaskStatus.WAITING)
                    .collect(java.util.stream.Collectors.toList());
            if (!waiting.isEmpty()) {
                sb.append("\n## 你当前待执行的定时提醒\n");
                for (ScheduledTask task : waiting) {
                    sb.append("- ").append(task.getName());
                    if (task.getFireAt() != null) {
                        sb.append("（").append(task.getFireAt().format(
                                java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")))
                                .append(" 执行）");
                    }
                    sb.append("\n");
                }
                sb.append("如果用户问及时钟或提醒，请参考以上信息。");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Failed to build pending task context, user={}", anonymize(userId), e);
            return sb.toString();
        }
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    private static Duration safeMemoryTimeout(Duration configured) {
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofHours(2)
                : configured;
    }

    private static final class UserConversation {
        private final List<ConversationMessage> messages = new ArrayList<>();

        private List<ConversationMessage> copyMessages() {
            return List.copyOf(messages);
        }

        private void remember(ConversationMessage message, int maxMessages) {
            messages.add(message);
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
