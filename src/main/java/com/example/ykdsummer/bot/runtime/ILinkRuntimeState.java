package com.example.ykdsummer.bot.runtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 保存“当前正在发生什么”的非敏感运行状态。
 *
 * <p>SDK 的回调线程会更新这里，浏览器的 HTTP 线程会同时读取这里，所以简单字段使用
 * {@code volatile} 保证线程间可见性，计数器使用 {@link AtomicLong} 保证并发累加不丢失。</p>
 *
 * <p>这个类刻意不保存或输出登录 token、contextToken、消息正文等敏感内容。
 * {@link Snapshot} 是提供给 {@code /api/ilink/status} 的只读快照。</p>
 */
@Component
public class ILinkRuntimeState {

    private volatile String connectionStatus = "DISABLED";
    private volatile String qrCodeUrl;
    private volatile String accountId;
    private volatile String lastMessageType;
    private volatile Instant lastMessageAt;
    private volatile String lastError;
    private final AtomicLong receivedMessages = new AtomicLong();
    private final AtomicLong sentMessages = new AtomicLong();

    /** 功能未启用。 */
    public void disabled() {
        connectionStatus = "DISABLED";
    }

    /** 已开始创建 SDK 客户端，但尚未确认登录。 */
    public void starting() {
        connectionStatus = "STARTING";
        lastError = null;
    }

    /** SDK 已返回二维码，正在等待手机扫码确认。 */
    public void waitingForQrCode(String url) {
        connectionStatus = "WAITING_FOR_QR_SCAN";
        qrCodeUrl = url;
        lastError = null;
    }

    /** SDK 已恢复或保存有效会话，可以开始收发消息。 */
    public void authenticated(String authenticatedAccountId) {
        connectionStatus = "CONNECTED";
        accountId = authenticatedAccountId;
        qrCodeUrl = null;
        lastError = null;
    }

    /** 记录一条入站消息；type 是本项目翻译后的类型名称。 */
    public void messageReceived(String type) {
        receivedMessages.incrementAndGet();
        lastMessageType = type;
        lastMessageAt = Instant.now();
    }

    /** 记录一次成功发送，并清除上一次发送错误。 */
    public void messageSent() {
        sentMessages.incrementAndGet();
        lastError = null;
    }

    /** 记录单条消息发送失败，但连接本身仍可能正常，所以不把状态改成 ERROR。 */
    public void messageDeliveryFailed(String message) {
        lastError = message;
    }

    /** 记录启动或轮询层面的故障，此时整个连接进入 ERROR。 */
    public void failed(String message) {
        connectionStatus = "ERROR";
        lastError = message;
    }

    /**
     * 复制当前状态供 HTTP 接口读取，避免 Controller 直接修改内部字段。
     */
    public Snapshot snapshot(boolean enabled, boolean polling) {
        return new Snapshot(
                enabled,
                polling,
                connectionStatus,
                qrCodeUrl,
                accountId,
                receivedMessages.get(),
                sentMessages.get(),
                lastMessageType,
                lastMessageAt,
                lastError
        );
    }

    /** 对外只读的状态数据结构。 */
    public record Snapshot(
            boolean enabled,
            boolean polling,
            String connectionStatus,
            String qrCodeUrl,
            String accountId,
            long receivedMessages,
            long sentMessages,
            String lastMessageType,
            Instant lastMessageAt,
            String lastError
    ) {
    }
}
