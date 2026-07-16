package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.message.ILinkMessageType;
import com.example.ykdsummer.bot.message.RecentMessageIds;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import com.example.ykdsummer.bot.session.ILinkSessionStore;
import io.github.morningwn.client.ILinkBot;
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
import java.util.stream.Collectors;

/**
 * iLink 的主业务编排类：管理 SDK 生命周期，并实现“收到文字后固定回复”的 Demo。
 *
 * <p>建议按下面顺序阅读：</p>
 * <ol>
 *     <li>{@link #start()}：Spring 启动后创建 SDK 客户端；</li>
 *     <li>{@code startAutoPull(this::handleInboundMessage)}：SDK 在后台长轮询微信消息；</li>
 *     <li>{@link #handleInboundMessage(WeixinMessage)}：SDK 每收到一条消息就回调本项目；</li>
 *     <li>本项目分类、去重、提取文字，再调用 SDK 的 {@code replyText} 回复；</li>
 *     <li>{@link #stop()}：Spring 关闭时释放 SDK 的轮询线程和网络资源。</li>
 * </ol>
 *
 * <p><strong>边界：</strong>HTTP 请求、协议序列化、鉴权、getupdates 长轮询和 sendmessage
 * 请求由第三方 Java SDK 封装；是否回复、回复什么、如何处理图片/语音则由本类决定。</p>
 */
@Service
public class ILinkBotService {

    private static final Logger log = LoggerFactory.getLogger(ILinkBotService.class);
    /** 单进程最多记住 1000 个近期 messageId，防止立即重复回复。 */
    private static final int RECENT_MESSAGE_WINDOW = 1_000;

    /** 启动时只接受最近两分钟的消息，避免恢复旧游标后回复很久以前的历史消息。 */
    private static final long STARTUP_MESSAGE_GRACE_MS = 120_000;

    private final ILinkProperties settings;
    private final ILinkSessionStore sessionStore;
    private final ILinkRuntimeState runtimeState;
    private final RecentMessageIds recentMessageIds = new RecentMessageIds(RECENT_MESSAGE_WINDOW);
    private final long startedAtMs = System.currentTimeMillis();

    /** 当前 SDK 客户端；volatile 让 HTTP 线程和 SDK 回调线程看到最新引用。 */
    private volatile ILinkBot bot;

    public ILinkBotService(
            ILinkProperties settings,
            ILinkSessionStore sessionStore,
            ILinkRuntimeState runtimeState
    ) {
        this.settings = settings;
        this.sessionStore = sessionStore;
        this.runtimeState = runtimeState;
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
            ILinkBot startedBot = new ILinkBot(clientConfig, "ykd-summer", sessionStore);

            // 把上次已确认的消息位置交给 SDK，避免每次重启都从旧消息重新开始。
            startedBot.setGetUpdatesBuf(sessionStore.loadCursor());
            bot = startedBot;

            /*
             * SDK 从这里启动后台 getupdates 长轮询。
             * this::handleInboundMessage 是 Java 方法引用：每收到一条消息，SDK 就调用下面的
             * handleInboundMessage(message)。这里不是我们自己写 while(true) 循环。
             */
            startedBot.startAutoPull(this::handleInboundMessage);
            log.info("iLink long polling started");
        } catch (RuntimeException exception) {
            runtimeState.failed(safeErrorMessage(exception));
            log.error("Cannot start iLink bot", exception);
        }
    }

    /** Spring 应用停止时自动调用，关闭 SDK，避免后台线程和连接泄漏。 */
    @PreDestroy
    public void stop() {
        ILinkBot currentBot = bot;
        bot = null;
        if (currentBot != null) {
            currentBot.close();
            log.info("iLink bot stopped");
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

        // 当前版本对图片/语音/文件/视频只做识别和日志记录，还没有下载或自动回复。
        logNonTextItems(message, items);

        // 找到本条消息中第一段非空文字；没有文字就结束，不发送固定回复。
        String text = items.stream()
                .filter(Objects::nonNull)
                .filter(item -> ILinkMessageType.from(item.type()) == ILinkMessageType.TEXT)
                .map(MessageItem::textItem)
                .filter(Objects::nonNull)
                .map(textItem -> textItem.text())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);

        if (text == null) {
            return;
        }

        // 先去重，防止 getupdates 重试时同一 messageId 被再次回复。
        if (recentMessageIds.contains(message.messageId())) {
            log.info("Skip duplicate iLink message {}", message.messageId());
            return;
        }
        // 恢复旧游标时可能短暂拉到历史消息，启动保护期内跳过过旧消息。
        if (isStaleAtStartup(message)) {
            recentMessageIds.remember(message.messageId());
            log.info("Skip stale iLink message {} from before this startup", message.messageId());
            return;
        }

        log.info("Received iLink text from {}: {}", message.fromUserId(), text);
        try {
            /*
             * replyText 是 SDK 封装的方法。SDK 会从入站 message 中取得回复目标和
             * contextToken，再发出 sendmessage；本项目只决定回复的文本内容。
             */
            requireRunningBot().replyText(message, settings.getFixedReply());
            recentMessageIds.remember(message.messageId());
            runtimeState.messageSent();
            log.info("Replied to iLink message {}", message.messageId());
        } catch (RuntimeException exception) {
            /*
             * 即使发送失败也不把异常抛回 SDK，否则整批消息可能被判定为未处理，旧消息会
             * 持续重放。这里记录错误和 messageId，让消息游标仍可继续向前。
             */
            recentMessageIds.remember(message.messageId());
            runtimeState.messageDeliveryFailed(safeErrorMessage(exception));
            log.warn(
                    "Could not reply to iLink message {}; continue so the message cursor can advance",
                    message.messageId(),
                    exception
            );
        }
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
                        message.fromUserId(),
                        item.voiceItem().text()
                );
            } else if (type != ILinkMessageType.TEXT) {
                log.info("Received iLink {} item from {}", type, message.fromUserId());
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
}
