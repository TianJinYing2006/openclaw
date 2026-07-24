package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.config.VideoProcessingProperties;
import com.example.ykdsummer.bot.message.ILinkMessageType;
import com.example.ykdsummer.bot.message.MessageExtractor;
import com.example.ykdsummer.bot.message.RecentMessageIds;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import com.example.ykdsummer.bot.session.ILinkSessionStore;
import com.example.ykdsummer.bot.video.ILinkVideoDownloader;
import io.github.morningwn.client.ILinkBot;
import io.github.morningwn.client.ILinkClient;
import io.github.morningwn.client.ILinkClientConfig;
import io.github.morningwn.protocol.MessageItem;
import io.github.morningwn.protocol.ProtocolValues;
import io.github.morningwn.protocol.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * iLink 的主业务编排类：管理 SDK 生命周期，把收到的消息交给命令/AI 回复服务。
 *
 * <p>建议按下面顺序阅读：</p>
 * <ol>
 *     <li>{@link #start()}：Spring 启动后创建 SDK 客户端；</li>
 *     <li>{@code startAutoPull(this::handleInboundMessage)}：SDK 在后台长轮询微信消息；</li>
 *     <li>{@link #handleInboundMessage(WeixinMessage)}：SDK 每收到一条消息就回调本项目；</li>
 *     <li>本项目分类、去重，交给 {@link ILinkReplyService} 决定回复内容；</li>
 *     <li>再调用 SDK 的 {@code replyText} 把统一的文字回复发回微信；</li>
 *     <li>{@link #stop()}：Spring 关闭时释放 SDK 的轮询线程和网络资源。</li>
 * </ol>
 *
 * <p><strong>边界：</strong>HTTP 请求、协议序列化、鉴权、getupdates 长轮询和 sendmessage
 * 请求由第三方 Java SDK 封装；是否回复、回复什么以及图片/语音如何进入 AI，主要由
 * {@link ILinkReplyService} 决定，本类负责把这些步骤串起来。</p>
 */
@Service
public class ILinkBotService {

    private static final Logger log = LoggerFactory.getLogger(ILinkBotService.class);
    /** 单进程最多记住 1000 个近期 messageId，防止立即重复回复。 */
    private static final int RECENT_MESSAGE_WINDOW = 1_000;

    /** 启动时只接受最近两分钟的消息，避免恢复旧游标后回复很久以前的历史消息。 */
    private static final long STARTUP_MESSAGE_GRACE_MS = 120_000;
    private static final String VIDEO_QUEUE_BUSY_REPLY = "当前视频任务较多，请稍后重试";
    private static final String TEXT_QUEUE_BUSY_REPLY = "当前对话任务较多，请稍后重试";
    private static final int TEXT_EXECUTOR_PARTITIONS = 8;

    private final ILinkProperties settings;
    private final ILinkSessionStore sessionStore;
    private final ILinkRuntimeState runtimeState;
    private final MessageExtractor messageExtractor;
    private final ILinkReplyService replyService;
    private final ILinkMessageRateLimiter rateLimiter;
    private final ILinkMediaDownloader mediaDownloader;
    private final ILinkFileDownloader fileDownloader;
    private final ILinkVideoDownloader videoDownloader;
    private final RecentMessageIds recentMessageIds = new RecentMessageIds(RECENT_MESSAGE_WINDOW);
    private final long startedAtMs = System.currentTimeMillis();

    /**
     * 8 条文字队列。相同用户总是进入同一条单线程队列，因此文字消息严格保持提交顺序；
     * 不同用户通常可以并行处理；每条队列都有等待上限，慢模型不会造成无限堆积。
     */
    private final ExecutorService[] textReplyExecutors;

    /**
     * “生图：”任务使用的独立线程池，最多同时处理两张生成任务。
     * 普通文字和用户发来图片让模型理解的任务都不会进入这里。
     */
    /**
     * 视频下载、FFmpeg 解码和多图模型调用都比较重，因此只允许一个任务执行，并限制等待数量。
     * AbortPolicy 会在队列满时抛出 RejectedExecutionException，让用户收到“稍后重试”。
     */
    private final ExecutorService videoReplyExecutor;

    /** 当前 SDK 客户端；volatile 让 HTTP 线程和 SDK 回调线程看到最新引用。 */
    private volatile ILinkBot bot;
    /** 与 ILinkBot 共用的底层客户端，供图片、文件和视频从腾讯 CDN 下载解密。 */
    private volatile ILinkClient lowLevelClient;

    public ILinkBotService(
            ILinkProperties settings,
            ILinkSessionStore sessionStore,
            ILinkRuntimeState runtimeState,
            ILinkReplyService replyService,
            ILinkMessageRateLimiter rateLimiter,
            MessageExtractor messageExtractor,
            ILinkMediaDownloader mediaDownloader,
            ILinkFileDownloader fileDownloader,
            ILinkVideoDownloader videoDownloader,
            VideoProcessingProperties videoProperties
    ) {
        // 这些对象都由 Spring 创建并传入；构造器本身不会连接腾讯服务器。
        this.settings = settings;
        this.sessionStore = sessionStore;
        this.runtimeState = runtimeState;
        this.replyService = replyService;
        this.messageExtractor = messageExtractor;
        this.rateLimiter = rateLimiter;
        this.mediaDownloader = mediaDownloader;
        this.fileDownloader = fileDownloader;
        this.videoDownloader = videoDownloader;
        this.textReplyExecutors = IntStream.range(0, TEXT_EXECUTOR_PARTITIONS)
                .mapToObj(index -> boundedExecutor(
                        1,
                        settings.getTextQueueCapacity(),
                        "ilink-text-reply-" + index
                ))
                .toArray(ExecutorService[]::new);
        this.videoReplyExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(videoProperties.getQueueCapacity()),
                runnable -> daemonThread(runnable, "ilink-video-reply"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Spring 完成依赖注入后自动调用一次，不需要再在 PowerShell 手动启动第二个程序。
     *
     * <p>如果 {@code ilink.enabled=false}，只跳过 iLink 连接，Spring Web 服务仍然可以运行。</p>
     */
    @PostConstruct
    public void start() {
        if (!settings.isEnabled()) {
            runtimeState.disabled();
            log.info("iLink is disabled. Set ILINK_ENABLED=true to start QR login.");
            return;
        }

        runtimeState.starting();
        try {
            // 这是 SDK 自己的网络配置对象，本项目只把 application.properties 中的值交给它。
            ILinkClientConfig clientConfig = ILinkClientConfig.builder()
                    .baseUrl(settings.getBaseUrl())
                    .channelVersion(settings.getChannelVersion())
                    .build();

            /*
             * 创建 SDK 客户端，并把 sessionStore 注册为回调。
             * SDK 随后会通过 SessionHandler 自动加载旧会话或生成扫码二维码。
             */
            ILinkClient startedClient = new ILinkClient(clientConfig);
            ILinkBot startedBot = new ILinkBot(startedClient, clientConfig, "ykd-summer", sessionStore);

            // 把上次已确认的消息位置交给 SDK，避免每次重启都从旧消息重新开始。
            startedBot.setGetUpdatesBuf(sessionStore.loadCursor());
            lowLevelClient = startedClient;
            mediaDownloader.attach(startedClient);
            fileDownloader.attach(startedClient);
            videoDownloader.attach(startedClient);
            bot = startedBot;

            /*
             * SDK 从这里启动后台 getupdates 长轮询。
             * this::handleInboundMessage 是 Java 方法引用：每收到一条消息，SDK 就调用下面的
             * handleInboundMessage(message)。这里不是我们自己写 while(true) 循环。
             */
            startedBot.startAutoPull(this::handleInboundMessage);
            log.info("iLink long polling started");
        } catch (RuntimeException exception) {
            closeSdkClients();
            runtimeState.failed(safeErrorMessage(exception));
            log.error("Cannot start iLink bot", exception);
        }
    }

    /** Spring 应用停止时自动调用，关闭 SDK，避免后台线程和连接泄漏。 */
    @PreDestroy
    public void stop() {
        for (ExecutorService executor : textReplyExecutors) {
            executor.shutdownNow();
        }
        videoReplyExecutor.shutdownNow();
        closeSdkClients();
    }

    private void closeSdkClients() {
        ILinkBot currentBot = bot;
        ILinkClient currentClient = lowLevelClient;
        bot = null;
        lowLevelClient = null;
        // 先关闭 ILinkBot 的自动拉取，再解除下载器引用，最后关闭共用的 HTTP 客户端。
        if (currentBot != null) {
            currentBot.close();
            log.info("iLink bot stopped");
        }
        mediaDownloader.detach(currentClient);
        fileDownloader.detach(currentClient);
        videoDownloader.detach(currentClient);
        if (currentClient != null) {
            currentClient.close();
        }
    }

    /**
     * @return 当前公开状态；polling 表示 SDK 的自动拉取线程是否正在工作
     */
    public ILinkRuntimeState.Snapshot status() {
        ILinkBot currentBot = bot;
        return runtimeState.snapshot(settings.isEnabled(), currentBot != null && currentBot.isAutoPulling());
    }

    /**
     * 主动发送文字，供 {@code POST /api/ilink/send} 调用。
     *
     * <p>SDK 的 {@code sendText} 会封装 sendmessage 网络请求，但调用者仍要提供目标用户和
     * contextToken。它们应来自真实会话上下文，不能凭空编造，也不应写死到源码。</p>
     */
    public void sendText(String toUserId, String contextToken, String text) {
        ILinkBot currentBot = requireRunningBot();
        requireText(toUserId, "toUserId");
        requireText(contextToken, "contextToken");
        requireText(text, "text");
        currentBot.sendText(toUserId, contextToken, text);
        runtimeState.messageSent();
    }

    /**
     * SDK 的单条入站消息回调，也是本项目最重要的业务入口。
     *
     * <p>{@link WeixinMessage} 是 SDK 已经反序列化好的对象。一条消息可以包含多个
     * {@link MessageItem}，所以代码不能假设只有一段文字。</p>
     *
     * @param message SDK 从 getupdates 响应中解析出的微信消息
     */
    void handleInboundMessage(WeixinMessage message) {
        // 这里只处理普通用户消息；系统事件、状态通知等协议消息直接忽略。
        if (message == null || !Objects.equals(message.messageType(), ProtocolValues.MESSAGE_TYPE_USER)) {
            return;
        }

        // SDK 可能返回 null itemList，先转换为空列表，后面流式处理就不会空指针。
        List<MessageItem> items = message.itemList() == null ? List.of() : message.itemList();

        // 把所有 item 的协议整数类型翻译为可读名称，供状态接口观察。
        String messageTypes = items.stream()
                .filter(Objects::nonNull)
                .map(MessageItem::type)
                .map(ILinkMessageType::from)
                .map(Enum::name)
                .distinct()
                .collect(Collectors.joining(","));
        runtimeState.messageReceived(messageTypes.isBlank() ? ILinkMessageType.UNKNOWN.name() : messageTypes);

        // 记录消息类型，但不记录用户的文字、语音转写或图片内容。
        logNonTextItems(message, items);

        // 先去重，防止 getupdates 重试时同一 messageId 被再次回复。
        if (recentMessageIds.contains(message.messageId())) {
            log.info("Skip duplicate iLink message {}", message.messageId());
            return;
        }
        // 立即记住，缩小 SDK 重试与入队之间的重复投递窗口
        recentMessageIds.remember(message.messageId());
        // 恢复旧游标时可能短暂拉到历史消息，启动保护期内跳过过旧消息。
        if (isStaleAtStartup(message)) {
            log.info("Skip stale iLink message {} from before this startup", message.messageId());
            return;
        }

        String rateLimitType = rateLimitType(items);
        if (!rateLimiter.tryAcquire(message.fromUserId(), rateLimitType)) {
            log.info("Rate limited iLink message {}, user={}, type={}",
                    message.messageId(), anonymize(message.fromUserId()), rateLimitType);
            replyQueueBusy(message, "消息发送过快，请稍后再试");
            return;
        }

        log.info(
                "Processing iLink message {}, user={}, itemCount={}, types={}",
                message.messageId(),
                anonymize(message.fromUserId()),
                items.size(),
                messageTypes
        );
        /*
         * 从这里开始可能要等待外部 AI 数十秒，必须移交给回复线程池。
         * SDK 的 ilink-auto-pull 线程立即返回，继续维持 getupdates 长轮询连接。
         */
        /*
         * 这里的"记住"表示本进程已经接受这条消息并准备入队，不代表 AI 已回答成功。
         * 先记住可以挡住 SDK 短时间内的重复投递。
         */
        try {
            List<MessageItem> safeItems = List.copyOf(items);
            /*
             * 只有“生图：描述”进入图片线程池。普通文字、语音转写、用户上传图片或文件的理解请求，
             * 都按用户 ID 分配到文字队列，以便多轮上下文保持顺序。
             */
            boolean videoMessage = messageExtractor.extract(safeItems).hasVideo();
            ExecutorService executor = videoMessage
                    ? videoReplyExecutor
                    : textExecutorFor(message.fromUserId());
            executor.execute(() -> processReply(message, safeItems));
            /*
             * execute 成功后本方法立即返回，SDK 就可能认为业务回调已完成，并提交本批建议游标。
             * 好处是慢 AI 不会卡住 getupdates；代价是程序若在入队后、回复前崩溃，内存任务
             * 不会随游标自动恢复。当前 Demo 没有持久化任务队列。
             */
        } catch (RejectedExecutionException exception) {
            boolean videoMessage = messageExtractor.extract(items).hasVideo();
            String reason = videoMessage ? VIDEO_QUEUE_BUSY_REPLY : TEXT_QUEUE_BUSY_REPLY;
            runtimeState.messageDeliveryFailed(reason);
            log.warn("Could not schedule reply for iLink message {}", message.messageId());
            replyQueueBusy(message, reason);
        }
    }

    /** 任一有界队列满时直接发送固定提示，不再调用外部 AI。 */
    private void replyQueueBusy(WeixinMessage message, String reason) {
        try {
            requireRunningBot().replyText(message, reason);
            runtimeState.messageSent();
        } catch (RuntimeException exception) {
            log.warn("Could not send queue busy reply for message {}", message.messageId());
        }
    }

    /** 在独立工作线程中调用大模型并发送回复，不阻塞 iLink 长轮询。 */
    private void processReply(WeixinMessage message, List<MessageItem> items) {
        try {
            ILinkReply reply = replyService.createReply(message, items, status());
            if (reply == null) {
                return;
            }
            /*
             * replyText 是 SDK 封装的方法。SDK 会从入站 message 中取得回复目标和
             * contextToken，再发出 sendmessage；本项目只决定回复的文本内容。
             */
            ILinkBot runningBot = requireRunningBot();
            if (reply instanceof ILinkReply.Text textReply) {
                if (textReply.value() == null || textReply.value().isBlank()) {
                    return;
                }
                runningBot.replyText(message, textReply.value());
            } else if (reply instanceof ILinkReply.Image imageReply) {
                /*
                 * 本项目交给 SDK 的是原始 PNG 字节。SDK 内部还会生成 AES key、加密图片、
                 * 请求腾讯 CDN 上传地址、上传密文、组装 ImageItem，最后调用 sendmessage。
                 * 因此微信收到的是可直接显示的图片，而不是需要用户点击的外部链接。
                 */
                runningBot.sendImage(message.fromUserId(), message.contextToken(), imageReply.bytes());
            } else if (reply instanceof ILinkReply.AudioFile audioReply) {
                // TTS 已在上一层完成：这里只发送 MP3 文件，不额外发送文字答案。
                runningBot.sendFile(message.fromUserId(), message.contextToken(), audioReply.fileName(), audioReply.bytes());
            } else if (reply instanceof ILinkReply.DocumentFile documentReply) {
                // 文档结果先发文件，再发状态说明，手机端可以紧接着继续输入修改要求或命令。
                runningBot.sendFile(message.fromUserId(), message.contextToken(),
                        documentReply.fileName(), documentReply.bytes());
                if (documentReply.followUpText() != null && !documentReply.followUpText().isBlank()) {
                    runningBot.replyText(message, documentReply.followUpText());
                }
            }
            runtimeState.messageSent();
            log.info("Replied to iLink message {}", message.messageId());
        } catch (RuntimeException exception) {
            /*
             * 即使发送失败也不把异常抛回 SDK，否则整批消息可能被判定为未处理，旧消息会
             * 持续重放。这里记录错误和 messageId，让消息游标仍可继续向前。
             */
            runtimeState.messageDeliveryFailed(safeErrorMessage(exception));
            log.warn(
                    "Could not reply to iLink message {}; continue so the message cursor can advance",
                    message.messageId(),
                    exception
            );
        }
    }

    private ExecutorService textExecutorFor(String userId) {
        /*
         * 相同 userId 每次算出的 index 相同，所以会进入同一条单线程队列并按顺序执行。
         * 这里只建了 8 条队列，不同用户也可能算到同一个 index；这时他们会共用队列，
         * 但 AiChatService 中的聊天记录仍按真实 userId 分开。
         */
        int index = Math.floorMod(Objects.hashCode(userId), textReplyExecutors.length);
        return textReplyExecutors[index];
    }

    private static String rateLimitType(List<MessageItem> items) {
        boolean hasImage = false;
        boolean hasFile = false;
        boolean hasVoice = false;
        for (MessageItem item : items) {
            if (item == null) {
                continue;
            }
            ILinkMessageType type = ILinkMessageType.from(item.type());
            if (type == ILinkMessageType.VIDEO) return "video";
            if (type == ILinkMessageType.IMAGE) hasImage = true;
            if (type == ILinkMessageType.FILE) hasFile = true;
            if (type == ILinkMessageType.VOICE) hasVoice = true;
        }
        if (hasImage) return "image";
        if (hasFile) return "file";
        if (hasVoice) return "voice";
        return "text";
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static ExecutorService boundedExecutor(int threadCount, int queueCapacity, String threadName) {
        return new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> daemonThread(runnable, threadName),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /** 判断消息创建时间是否早于“本次启动时间减去两分钟”。 */
    private boolean isStaleAtStartup(WeixinMessage message) {
        Long createTimeMs = message.createTimeMs();
        return createTimeMs != null && createTimeMs < startedAtMs - STARTUP_MESSAGE_GRACE_MS;
    }

    /**
     * 演示不同 MessageItem 的分支入口。
     *
     * <p>语音对象若带有 {@code text()}，它通常是微信侧提供的语音转文字结果；图片、文件和
     * 视频目前只记录类型。后续真实处理应在这些分支中读取对应 item，再交给独立服务。</p>
     */
    private void logNonTextItems(WeixinMessage message, List<MessageItem> items) {
        for (MessageItem item : items) {
            if (item == null) {
                continue;
            }
            ILinkMessageType type = ILinkMessageType.from(item.type());
            if (type == ILinkMessageType.VOICE && item.voiceItem() != null) {
                log.info(
                        "Received iLink voice from {}, transcript={}",
                        anonymize(message.fromUserId()),
                        item.voiceItem().text() != null && !item.voiceItem().text().isBlank()
                );
            } else if (type != ILinkMessageType.TEXT) {
                log.info("Received iLink {} item from {}", type, anonymize(message.fromUserId()));
            }
        }
    }

    /** 获取可用 SDK 客户端；未启用或轮询未启动时立即给出明确错误。 */
    private ILinkBot requireRunningBot() {
        ILinkBot currentBot = bot;
        if (currentBot == null || !currentBot.isAutoPulling()) {
            throw new IllegalStateException("iLink 尚未启用或正在等待启动");
        }
        return currentBot;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    private static String safeErrorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
