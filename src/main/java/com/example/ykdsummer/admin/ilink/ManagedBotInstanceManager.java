package com.example.ykdsummer.admin.ilink;

import com.example.ykdsummer.admin.service.AdminPlatformService;
import com.example.ykdsummer.admin.service.SessionCipher;
import com.example.ykdsummer.ai.tool.ImageTaskCompletionEvent;
import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.message.ILinkMessageDeduplicator;
import com.example.ykdsummer.bot.message.ILinkMessageType;
import com.example.ykdsummer.bot.message.RecentMessageIds;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import com.example.ykdsummer.bot.runtime.ILinkReplyContextStore;
import com.example.ykdsummer.bot.service.ILinkMessageRateLimiter;
import com.example.ykdsummer.bot.service.ILinkReply;
import com.example.ykdsummer.bot.service.ILinkReplyService;
import com.example.ykdsummer.persistence.ManagedInstanceScope;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.morningwn.client.ILinkBot;
import io.github.morningwn.client.ILinkClient;
import io.github.morningwn.client.ILinkClientConfig;
import io.github.morningwn.protocol.MessageItem;
import io.github.morningwn.protocol.ProtocolValues;
import io.github.morningwn.protocol.WeixinMessage;
import jakarta.annotation.PreDestroy;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Owns independent iLink SDK clients inside one JVM. Every platform user has exactly one active instance;
 * archived instances remain encrypted in MySQL until the administrator restores or permanently deletes them.
 */
@Component
@ConditionalOnProperty(prefix = "app.admin", name = "enabled", havingValue = "true")
public class ManagedBotInstanceManager implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ManagedBotInstanceManager.class);
    private static final int PARTITIONS = 8;
    private final AdminPlatformService platform;
    private final SessionCipher cipher;
    private final ILinkProperties ilinkProperties;
    private final ILinkReplyService replyService;
    private final ILinkMessageRateLimiter rateLimiter;
    private final ILinkReplyContextStore replyContexts;
    private volatile ILinkMessageDeduplicator messageDeduplicator;
    private final Map<String, ManagedRunner> runners = new ConcurrentHashMap<>();
    private final Cache<String, ReplyTarget> replyTargets = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();
    private final ExecutorService[] replyExecutors;

    public ManagedBotInstanceManager(
            AdminPlatformService platform,
            SessionCipher cipher,
            ILinkProperties ilinkProperties,
            ILinkReplyService replyService,
            ILinkMessageRateLimiter rateLimiter,
            ILinkReplyContextStore replyContexts
    ) {
        this.platform = platform;
        this.cipher = cipher;
        this.ilinkProperties = ilinkProperties;
        this.replyService = replyService;
        this.rateLimiter = rateLimiter;
        this.replyContexts = replyContexts;
        this.replyExecutors = java.util.stream.IntStream.range(0, PARTITIONS)
                .mapToObj(index -> new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(Math.max(1, ilinkProperties.getTextQueueCapacity())),
                        runnable -> daemonThread(runnable, "managed-ilink-reply-" + index),
                        new ThreadPoolExecutor.AbortPolicy()))
                .toArray(ExecutorService[]::new);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureMessageDeduplicator(ILinkMessageDeduplicator messageDeduplicator) {
        this.messageDeduplicator = messageDeduplicator;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments arguments) {
        if (!ilinkProperties.isEnabled()) {
            log.info("Managed iLink instances are configured but ilink.enabled=false");
            return;
        }
        bootstrapLegacySession();
        // Only stored, previously connected sessions start after a JVM restart. Fresh users request QR manually.
        platform.activeInstances().stream()
                .filter(instance -> "CONNECTED".equals(instance.connectionStatus()))
                .forEach(instance -> start(instance.instanceId()));
    }

    public void requestQr(long platformUserId) {
        AdminPlatformService.UserOverview user = platform.findUser(platformUserId)
                .orElseThrow(() -> new IllegalArgumentException("Platform user was not found"));
        if (user.instanceId() == null) throw new IllegalStateException("Platform user has no active bot instance");
        stop(user.instanceId());
        platform.clearSession(user.instanceId());
        platform.requestBinding(platformUserId);
        start(user.instanceId());
    }

    public void stop(String instanceId) {
        ManagedRunner runner = runners.remove(instanceId);
        if (runner != null) runner.close();
    }

    /** Starts an existing restored session; if its credential is expired the SDK requests a fresh QR code. */
    public void resume(String instanceId) {
        start(instanceId);
    }

    public String qrCodeUrl(String instanceId) {
        ManagedRunner runner = runners.get(instanceId);
        return runner == null ? "" : runner.status().qrCodeUrl();
    }

    public ILinkRuntimeState.Snapshot status(String instanceId) {
        ManagedRunner runner = runners.get(instanceId);
        return runner == null ? null : runner.status();
    }

    /** Returns true only when the completed image belongs to a currently managed conversation. */
    public boolean sendCompletedImage(ImageTaskCompletionEvent event) {
        if (event == null || event.imageBytes().length == 0) return false;
        ReplyTarget target = replyTargets.getIfPresent(event.userId());
        String instanceId = target == null ? ManagedInstanceScope.parse(event.userId()).instanceId() : target.instanceId();
        String contextToken = target == null ? replyContexts.find(event.userId()).orElse(null) : target.contextToken();
        if (instanceId == null || contextToken == null || contextToken.isBlank()) return false;
        ManagedRunner runner = runners.get(instanceId);
        if (runner == null) return false;
        try {
            runner.sendImage(rawUserId(event.userId()), contextToken, event.imageBytes());
            return true;
        } catch (RuntimeException exception) {
            log.warn("Could not send completed managed image task {}, instance={}", event.taskId(), instanceId, exception);
            return true;
        }
    }

    /** Sends a durable background notification to a user of the matching managed bot instance. */
    public boolean sendText(String scopedUserId, String contextToken, String text) {
        ManagedInstanceScope scope = ManagedInstanceScope.parse(scopedUserId);
        if (!scope.managed() || contextToken == null || contextToken.isBlank() || text == null || text.isBlank()) {
            return false;
        }
        ManagedRunner runner = runners.get(scope.instanceId());
        if (runner == null) return false;
        try {
            runner.sendText(rawUserId(scopedUserId), contextToken, text);
            return true;
        } catch (RuntimeException exception) {
            log.warn("Could not send managed background text, instance={}, user={}", scope.instanceId(),
                    anonymize(scopedUserId), exception);
            return false;
        }
    }

    /** Sends a background image only through the managed bot that owns the scoped user. */
    public boolean sendImage(String scopedUserId, String contextToken, byte[] bytes) {
        ManagedInstanceScope scope = ManagedInstanceScope.parse(scopedUserId);
        if (!scope.managed() || contextToken == null || contextToken.isBlank() || bytes == null || bytes.length == 0) {
            return false;
        }
        ManagedRunner runner = runners.get(scope.instanceId());
        if (runner == null) return false;
        try {
            runner.sendImage(rawUserId(scopedUserId), contextToken, bytes);
            return true;
        } catch (RuntimeException exception) {
            log.warn("Could not send managed background image, instance={}, user={}", scope.instanceId(),
                    anonymize(scopedUserId), exception);
            return false;
        }
    }

    /** Sends a background file only through the managed bot that owns the scoped user. */
    public boolean sendFile(String scopedUserId, String contextToken, String fileName, byte[] bytes) {
        ManagedInstanceScope scope = ManagedInstanceScope.parse(scopedUserId);
        if (!scope.managed() || contextToken == null || contextToken.isBlank()
                || fileName == null || fileName.isBlank() || bytes == null || bytes.length == 0) {
            return false;
        }
        ManagedRunner runner = runners.get(scope.instanceId());
        if (runner == null) return false;
        try {
            runner.sendFile(rawUserId(scopedUserId), contextToken, fileName, bytes);
            return true;
        } catch (RuntimeException exception) {
            log.warn("Could not send managed background file, instance={}, user={}", scope.instanceId(),
                    anonymize(scopedUserId), exception);
            return false;
        }
    }

    private void start(String instanceId) {
        runners.computeIfAbsent(instanceId, id -> {
            AdminPlatformService.ManagedInstance instance = platform.activeInstances().stream()
                    .filter(value -> value.instanceId().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Managed bot instance was not found"));
            ManagedRunner runner = new ManagedRunner(instance);
            runner.start();
            return runner;
        });
    }

    private void bootstrapLegacySession() {
        if (platform.hasPlatformUsers()) return;
        Path sessionFile = ilinkProperties.getSessionFile().toAbsolutePath().normalize();
        if (!Files.isRegularFile(sessionFile)) return;
        try {
            String raw = Files.readString(sessionFile, StandardCharsets.UTF_8);
            Properties values = new Properties();
            values.load(new StringReader(raw));
            String accountId = values.getProperty("accountId", "");
            if (accountId.isBlank() || values.getProperty("token", "").isBlank()) return;
            platform.bootstrapExistingSession(cipher.encrypt(raw), accountId);
            Files.deleteIfExists(sessionFile);
            log.info("Migrated the existing local iLink session into managed user 管理员本人");
        } catch (Exception exception) {
            log.warn("Could not migrate the existing local iLink session", exception);
        }
    }

    @PreDestroy
    public void close() {
        runners.values().forEach(ManagedRunner::close);
        runners.clear();
        for (ExecutorService executor : replyExecutors) executor.shutdownNow();
    }

    private ExecutorService executorFor(String scopedUserId) {
        return replyExecutors[Math.floorMod(Objects.hashCode(scopedUserId), replyExecutors.length)];
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** The persisted application identity is managed:{instanceId}:{wechatUserId}; iLink only accepts the suffix. */
    private static String rawUserId(String scopedUserId) {
        ManagedInstanceScope scope = ManagedInstanceScope.parse(scopedUserId);
        if (!scope.managed()) return scopedUserId == null ? "" : scopedUserId;
        int offset = "managed:".length() + scope.instanceId().length() + 1;
        return scopedUserId != null && scopedUserId.length() > offset ? scopedUserId.substring(offset) : "";
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    private final class ManagedRunner {
        private final AdminPlatformService.ManagedInstance instance;
        private final ILinkRuntimeState runtimeState = new ILinkRuntimeState();
        private final RecentMessageIds recentMessages = new RecentMessageIds(1_000);
        private final long startedAtMs = System.currentTimeMillis();
        private volatile ILinkClient client;
        private volatile ILinkBot bot;

        private ManagedRunner(AdminPlatformService.ManagedInstance instance) { this.instance = instance; }

        private void start() {
            runtimeState.starting();
            platform.updateConnection(instance.instanceId(), "STARTING", "");
            try {
                ILinkClientConfig config = ILinkClientConfig.builder()
                        .baseUrl(ilinkProperties.getBaseUrl())
                        .channelVersion(ilinkProperties.getChannelVersion())
                        .build();
                ManagedILinkSessionStore sessions = new ManagedILinkSessionStore(instance.instanceId(), platform, cipher, runtimeState);
                ILinkClient createdClient = new ILinkClient(config);
                ILinkBot createdBot = new ILinkBot(createdClient, config, "ykd-summer", sessions);
                createdBot.setGetUpdatesBuf(sessions.loadCursor());
                client = createdClient;
                bot = createdBot;
                createdBot.startAutoPull(this::handle);
                log.info("Managed iLink polling started, user={}, instance={}", instance.username(), instance.instanceId());
            } catch (RuntimeException exception) {
                runtimeState.failed(safeError(exception));
                platform.updateConnection(instance.instanceId(), "ERROR", safeError(exception));
                close();
                throw exception;
            }
        }

        private void handle(WeixinMessage message) {
            if (message == null || !Objects.equals(message.messageType(), ProtocolValues.MESSAGE_TYPE_USER)) return;
            if (!claimInboundMessage(message)) return;
            if (message.createTimeMs() != null && message.createTimeMs() < startedAtMs - 120_000) {
                return;
            }
            List<MessageItem> items = message.itemList() == null ? List.of() : List.copyOf(message.itemList());
            String scopedUserId = scopedUser(message.fromUserId());
            String type = messageType(items);
            runtimeState.messageReceived(type);
            if (!rateLimiter.tryAcquire(scopedUserId, rateLimitType(items))) {
                replyDirect(message, "消息发送过快，请稍后再试");
                return;
            }
            replyTargets.put(scopedUserId, new ReplyTarget(instance.instanceId(), message.fromUserId(), message.contextToken()));
            replyContexts.remember(scopedUserId, message.contextToken());
            try {
                executorFor(scopedUserId).execute(() -> processReply(message, items, scopedUserId));
            } catch (RejectedExecutionException exception) {
                runtimeState.messageDeliveryFailed("当前对话任务较多，请稍后重试");
                replyDirect(message, "当前对话任务较多，请稍后重试");
            }
        }

        private boolean claimInboundMessage(WeixinMessage message) {
            ILinkMessageDeduplicator deduplicator = messageDeduplicator;
            return deduplicator == null
                    ? recentMessages.claim(message.messageId()) || message.messageId() == null
                    : deduplicator.claim("managed:" + instance.instanceId(), message.messageId());
        }

        private void processReply(WeixinMessage message, List<MessageItem> items, String scopedUserId) {
            try {
                ILinkReply reply = replyService.createReply(message, items, status(), scopedUserId, client);
                if (reply instanceof ILinkReply.Text text && text.value() != null && !text.value().isBlank()) {
                    requireBot().replyText(message, text.value());
                } else if (reply instanceof ILinkReply.Image image) {
                    requireBot().sendImage(message.fromUserId(), message.contextToken(), image.bytes());
                    if (image.followUpText() != null && !image.followUpText().isBlank()) requireBot().replyText(message, image.followUpText());
                } else if (reply instanceof ILinkReply.ImageBatch images) {
                    for (byte[] image : images.images()) {
                        requireBot().sendImage(message.fromUserId(), message.contextToken(), image);
                    }
                    if (!images.followUpText().isBlank()) requireBot().replyText(message, images.followUpText());
                } else if (reply instanceof ILinkReply.AudioFile audio) {
                    requireBot().sendFile(message.fromUserId(), message.contextToken(), audio.fileName(), audio.bytes());
                } else if (reply instanceof ILinkReply.DocumentFile document) {
                    requireBot().sendFile(message.fromUserId(), message.contextToken(), document.fileName(), document.bytes());
                    if (document.followUpText() != null && !document.followUpText().isBlank()) requireBot().replyText(message, document.followUpText());
                }
                runtimeState.messageSent();
            } catch (RuntimeException exception) {
                runtimeState.messageDeliveryFailed(safeError(exception));
                log.warn("Could not reply through managed iLink instance {}", instance.instanceId(), exception);
            }
        }

        private void replyDirect(WeixinMessage message, String text) {
            try {
                requireBot().replyText(message, text);
                runtimeState.messageSent();
            } catch (RuntimeException exception) {
                runtimeState.messageDeliveryFailed(safeError(exception));
            }
        }

        private void sendImage(String toUserId, String contextToken, byte[] bytes) {
            requireBot().sendImage(toUserId, contextToken, bytes);
            runtimeState.messageSent();
        }

        private void sendFile(String toUserId, String contextToken, String fileName, byte[] bytes) {
            requireBot().sendFile(toUserId, contextToken, fileName, bytes);
            runtimeState.messageSent();
        }

        private void sendText(String toUserId, String contextToken, String text) {
            requireBot().sendText(toUserId, contextToken, text);
            runtimeState.messageSent();
        }

        private ILinkRuntimeState.Snapshot status() {
            ILinkBot current = bot;
            return runtimeState.snapshot(true, current != null && current.isAutoPulling());
        }

        private ILinkBot requireBot() {
            ILinkBot current = bot;
            if (current == null || !current.isAutoPulling()) throw new IllegalStateException("Managed iLink instance is not running");
            return current;
        }

        private void close() {
            ILinkBot currentBot = bot;
            ILinkClient currentClient = client;
            bot = null;
            client = null;
            if (currentBot != null) currentBot.close();
            if (currentClient != null) currentClient.close();
        }

        private String scopedUser(String userId) { return "managed:" + instance.instanceId() + ':' + (userId == null ? "unknown" : userId); }
    }

    private static String messageType(List<MessageItem> items) {
        return items.stream().filter(Objects::nonNull).map(MessageItem::type).map(ILinkMessageType::from)
                .map(Enum::name).distinct().reduce((first, second) -> first + ',' + second).orElse("UNKNOWN");
    }

    private static String rateLimitType(List<MessageItem> items) {
        boolean image = false, file = false, voice = false;
        for (MessageItem item : items) {
            if (item == null) continue;
            ILinkMessageType type = ILinkMessageType.from(item.type());
            if (type == ILinkMessageType.VIDEO) return "video";
            if (type == ILinkMessageType.IMAGE) image = true;
            if (type == ILinkMessageType.FILE) file = true;
            if (type == ILinkMessageType.VOICE) voice = true;
        }
        return image ? "image" : file ? "file" : voice ? "voice" : "text";
    }

    private static String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record ReplyTarget(String instanceId, String toUserId, String contextToken) { }
}
