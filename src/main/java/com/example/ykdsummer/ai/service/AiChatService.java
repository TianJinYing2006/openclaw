package com.example.ykdsummer.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AiProperties properties;
    private final LlmGateway gateway;
    /** key 是 iLink fromUserId，value 是该微信用户自己的最近对话。 */
    private final Cache<String, UserConversation> conversations;
    /**
     * 内容去重缓存：key = "userId::模型最终 prompt"，value = 上次的回答文本。
     * 60 秒内相同用户发送相同内容，直接返回缓存结果，防止 SDK 重复投递导致两次回复。
     */
    private final Cache<String, String> recentResponses;

    public AiChatService(AiProperties properties, LlmGateway gateway) {
        this.properties = properties;
        this.gateway = gateway;
        this.conversations = Caffeine.newBuilder()
                .maximumSize(properties.getMaxMemoryUsers())
                .expireAfterAccess(safeMemoryTimeout(properties.getMemoryIdleTimeout()))
                .build();
        this.recentResponses = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
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
        return answer(userId, prompt, prompt, images, files);
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
        return answer(userId, userPrompt, modelPrompt, List.of(), files == null ? List.of() : files);
    }

    private String answer(
            String userId,
            String memoryPrompt,
            String modelPrompt,
            List<AiImage> images,
            List<AiFile> files
    ) {
        if (!properties.isEnabled()) {
            return DISABLED_REPLY;
        }

        // 构造去重 key，在同步块内检查，避免并发时两条线程同时通过外部检查
        String dedupKey = userId + "::" + modelPrompt;

        // Caffeine 按访问时间自动过期，并对总用户数设置上限，避免长期运行后 Map 无限增长。
        UserConversation conversation = conversations.get(userId, ignored -> new UserConversation());
        /*
         * Caffeine 负责会话对象的容量和过期，不负责同一用户两次请求的历史顺序。
         * synchronized 锁住该用户自己的会话：同一用户必须一个问题回答完再记下一条；
         * 不同用户锁的是不同对象，仍然可以并行。
         */
        synchronized (conversation) {
            // 内容去重：60 秒内同一用户发送相同的模型 prompt，直接返回缓存
            String cached = recentResponses.getIfPresent(dedupKey);
            if (cached != null) {
                log.info("Dedup hit for user={}, returning cached response", anonymize(userId));
                return cached;
            }
            try {
                /*
                 * Completion 与 Responses 请求都设置为 store=false，服务端不替我们保存上下文。
                 * 所以每次调用都复制最近历史，并连同本轮 prompt/images 重新发给模型。
                 */
                LlmGateway.ModelReply reply = gateway.generate(
                        conversation.copyMessages(), modelPrompt, images, files);
                // 只有模型成功返回后才把这一问一答写入历史，失败提示不会污染下一轮上下文。
                conversation.remember(
                        new ConversationMessage(ConversationMessage.Role.USER, memoryText(memoryPrompt, images, files)),
                        properties.getMaxMemoryMessages()
                );
                conversation.remember(
                        new ConversationMessage(ConversationMessage.Role.ASSISTANT, reply.text()),
                        properties.getMaxMemoryMessages()
                );
                // 缓存去重 key，确保后续内容去重能命中
                recentResponses.put(dedupKey, reply.text());
                return reply.text();
            } catch (AiGatewayException exception) {
                log.warn(
                        "AI request failed, user={}, kind={}",
                        anonymize(userId),
                        exception.kind()
                );
                String errorReply = switch (exception.kind()) {
                    case AUTHENTICATION -> AUTH_ERROR_REPLY;
                    case EMPTY_RESPONSE -> EMPTY_REPLY;
                    case TEMPORARY_UNAVAILABLE -> UNAVAILABLE_REPLY;
                };
                // 缓存错误回复，确保 SDK 重试时命中缓存返回相同错误，避免两次回复
                recentResponses.put(dedupKey, errorReply);
                return errorReply;
           } catch (RuntimeException exception) {
               log.warn("Unexpected AI failure, user={}, type={}", anonymize(userId), exception.getClass().getSimpleName());
                recentResponses.put(dedupKey, UNAVAILABLE_REPLY);
               return UNAVAILABLE_REPLY;
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
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String model() {
        return properties.getModel();
    }

    int conversationCount() {
        conversations.cleanUp();
        return Math.toIntExact(conversations.estimatedSize());
    }

    /**
     * 图片只服务于当前模型请求，不把 Base64 大数据放进历史；历史中只留下"用户曾附带图片"的文字提示。
     */
    private static String memoryText(String prompt, List<AiImage> images, List<AiFile> files) {
        int imageCount = images == null ? 0 : images.size();
        List<AiFile> safeFiles = files == null ? List.of() : files;
        StringBuilder memory = new StringBuilder(prompt);
        if (imageCount > 0) {
            memory.append("\n[用户曾附带 ").append(imageCount).append(" 张图片]");
        }
        if (!safeFiles.isEmpty()) {
            memory.append("\n[用户曾附带文件：")
                    .append(safeFiles.stream().map(AiFile::fileName).collect(java.util.stream.Collectors.joining("、")))
                    .append(']');
        }
        return memory.toString();
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
        /** USER 和 ASSISTANT 消息交替存放，条数达到上限时从最旧消息开始删除。 */
        private final List<ConversationMessage> messages = new ArrayList<>();

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
}
