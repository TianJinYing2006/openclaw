package com.example.ykdsummer.bot.session;

import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import io.github.morningwn.client.ILinkAuthSession;
import io.github.morningwn.handler.SessionHandler;
import io.github.morningwn.protocol.QrCodeResponse;
import io.github.morningwn.protocol.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Properties;

/**
 * SDK 的会话回调实现：在本地保存登录凭据和消息游标。
 *
 * <p>这个类实现了 SDK 提供的 {@link SessionHandler}。下面带 {@code @Override} 的方法
 * 都是“SDK 在合适时机反向调用本项目”的回调，并不是 Controller 主动调用的接口：</p>
 * <ul>
 *     <li>启动时，SDK 调 {@link #loadSession()} 尝试恢复登录；</li>
 *     <li>扫码成功后，SDK 调 {@link #persistSession(ILinkAuthSession)} 保存新登录态；</li>
 *     <li>登录态失效时，SDK 调 {@link #clearSession(ILinkAuthSession)} 清理；</li>
 *     <li>需要扫码时，SDK 调 {@link #onQrcode(QrCodeResponse)} 交出二维码；</li>
 *     <li>一批消息处理后，SDK 调 {@link #confirmGetUpdatesBuf(String, String, List, boolean)}
 *     询问是否提交新的 getupdates 游标。</li>
 * </ul>
 *
 * <p>会话文件含 token，属于密码级敏感信息，不能打印内容、上传或提交到 Git。</p>
 *
 * <p>四个容易混淆的概念：</p>
 * <ul>
 *     <li><strong>Session：</strong>登录身份、token、服务地址和账号，决定“我是谁”；</li>
 *     <li><strong>Cursor：</strong>getupdates 已确认读取到的位置，决定“下次从哪里继续拉”；</li>
 *     <li><strong>contextToken：</strong>每条会话的回复上下文，决定“回复回哪个微信对话”；</li>
 *     <li><strong>messageId：</strong>一条消息的唯一编号，本项目用它做近期去重。</li>
 * </ul>
 * <p>本类只保存前两个。contextToken 在 {@code WeixinMessage} 中随消息传递，messageId
 * 由 {@code RecentMessageIds} 在内存中管理。</p>
 */
@Component
public class ILinkSessionStore implements SessionHandler {

    private static final Logger log = LoggerFactory.getLogger(ILinkSessionStore.class);

    private final ILinkProperties settings;
    private final ILinkRuntimeState runtimeState;

    /** 当前内存中的 SDK 登录会话；null 表示尚未登录或会话已清除。 */
    private ILinkAuthSession currentSession;

    /** 已确认处理到的消息位置；作用类似“读到第几页”的书签。 */
    private String currentCursor = "";

    public ILinkSessionStore(ILinkProperties settings, ILinkRuntimeState runtimeState) {
        this.settings = settings;
        this.runtimeState = runtimeState;
    }

    /**
     * SDK 启动时调用：从磁盘恢复上次扫码得到的登录态。
     *
     * @return 找到且解析成功时返回 SDK 会话；没有文件或读取失败时返回 null，SDK 将进入扫码流程
     */
    @Override
    public synchronized ILinkAuthSession loadSession() {
        Path file = settings.getSessionFile().toAbsolutePath().normalize();
        if (Files.notExists(file)) {
            return null;
        }

        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
            String token = required(values, "token");
            String baseUrl = required(values, "baseUrl");
            String accountId = required(values, "accountId");
            String userId = required(values, "userId");
            currentCursor = values.getProperty("cursor", "");
            // ILinkAuthSession 是 SDK 定义的数据结构；我们只负责安全地保存和还原它。
            currentSession = new ILinkAuthSession(token, baseUrl, accountId, userId);
            runtimeState.authenticated(accountId);
            log.info("Loaded saved iLink session for account {}", accountId);
            return currentSession;
        } catch (Exception exception) {
            log.warn("Cannot load iLink session from {}", file, exception);
            runtimeState.failed("无法读取本地 iLink 会话文件");
            return null;
        }
    }

    /**
     * SDK 在扫码登录成功或会话更新时调用：把新凭据写入本地文件。
     *
     * @param session SDK 交给本项目保存的登录会话
     */
    @Override
    public synchronized void persistSession(ILinkAuthSession session) {
        currentSession = session;
        try {
            writeState();
            runtimeState.authenticated(session.accountId());
            log.info("Saved iLink session for account {}", session.accountId());
        } catch (IOException exception) {
            runtimeState.failed("无法保存 iLink 登录会话");
            throw new IllegalStateException("Cannot persist iLink session", exception);
        }
    }

    /**
     * SDK 判断登录态已过期时调用：删除旧凭据，使后续流程重新扫码。
     */
    @Override
    public synchronized void clearSession(ILinkAuthSession expiredSession) {
        currentSession = null;
        currentCursor = "";
        Path file = settings.getSessionFile().toAbsolutePath().normalize();
        try {
            Files.deleteIfExists(file);
            runtimeState.starting();
            log.info("Cleared expired iLink session");
        } catch (IOException exception) {
            runtimeState.failed("无法清除过期的 iLink 会话");
            log.warn("Cannot delete expired iLink session file {}", file, exception);
        }
    }

    /**
     * SDK 需要用户扫码时调用：把二维码 URL 放入运行状态，供浏览器接口跳转。
     */
    @Override
    public void onQrcode(QrCodeResponse response) {
        runtimeState.waitingForQrCode(response.qrcodeImgContent());
        log.info("Please scan the iLink QR code: {}", response.qrcodeImgContent());
    }

    /**
     * SDK 处理完一批 getupdates 消息后调用，决定是否接受服务端建议的新游标。
     *
     * <p>{@code currentGetUpdatesBuf} 是本批之前的位置，{@code suggestedGetUpdatesBuf}
     * 是服务端建议的新位置。只有整批消息都成功交给业务回调（fullyProcessed=true）且新游标
     * 有效时才提交；否则返回旧游标，保留重新拉取的机会。</p>
     *
     * <p>写文件失败也返回旧游标，防止“内存认为处理过、磁盘却没记录”造成重启后状态不一致。</p>
     *
     * @return SDK 下一轮 getupdates 应使用的游标
     */
    @Override
    public synchronized String confirmGetUpdatesBuf(
            String currentGetUpdatesBuf,
            String suggestedGetUpdatesBuf,
            List<WeixinMessage> receivedMessages,
            boolean fullyProcessed
    ) {
        /*
         * fullyProcessed 的含义是 SDK 已把这一批逐条交给消息回调且回调没有抛出异常。
         * 当前 handleInboundMessage 入队成功就会返回，所以这里确认的是“已交给本地队列”，
         * 不是“AI 已经生成并成功发回微信”。
         */
        if (!fullyProcessed || suggestedGetUpdatesBuf == null || suggestedGetUpdatesBuf.isBlank()) {
            return currentGetUpdatesBuf;
        }

        /*
         * 三个游标的先后关系：
         * 1. currentGetUpdatesBuf 是 SDK 本轮请求前正在使用的旧游标；
         * 2. suggestedGetUpdatesBuf 是腾讯随本批响应给出的建议新游标；
         * 3. currentCursor 是本类准备持久化到 session.properties 的本地副本。
         * 写盘成功后 SDK 返回值、本地 currentCursor、文件 cursor 都成为建议新游标。
         */
        String previousCursor = currentCursor;
        currentCursor = suggestedGetUpdatesBuf;
        try {
            if (currentSession != null) {
                writeState();
                runtimeState.authenticated(currentSession.accountId());
            }
            return suggestedGetUpdatesBuf;
        } catch (IOException exception) {
            currentCursor = previousCursor;
            runtimeState.failed("无法保存消息游标");
            log.warn("Cannot persist iLink message cursor", exception);
            return currentGetUpdatesBuf;
        }
    }

    /**
     * Service 创建 SDK 客户端后调用，把磁盘恢复出的游标交还给 SDK。
     */
    public synchronized String loadCursor() {
        // start() 紧接 loadSession() 之后调用，所以这里通常已是磁盘文件中恢复出的 cursor。
        return currentCursor;
    }

    /** 把当前会话和游标一起写入临时文件，再整体替换目标文件。 */
    private void writeState() throws IOException {
        if (currentSession == null) {
            return;
        }

        Path target = settings.getSessionFile().toAbsolutePath().normalize();
        Path parent = target.getParent();
        Files.createDirectories(parent);

        Properties values = new Properties();
        values.setProperty("token", currentSession.token());
        values.setProperty("baseUrl", currentSession.baseUrl());
        values.setProperty("accountId", currentSession.accountId());
        values.setProperty("userId", currentSession.userId());
        values.setProperty("cursor", currentCursor == null ? "" : currentCursor);

        // 先完整写临时文件，再替换正式文件，避免程序中途退出留下半截 properties。
        Path temporary = Files.createTempFile(parent, "ilink-session-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, "Sensitive iLink session - do not commit");
            }
            moveAtomically(temporary, target);
            restrictFilePermissions(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 文件系统支持时使用原子替换；不支持时退化为普通覆盖移动。 */
    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(atomicFailure);
                throw fallbackFailure;
            }
        }
    }

    /** Linux/macOS 尽量把文件权限收紧为仅当前用户可读写。 */
    private static void restrictFilePermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 不提供 POSIX 权限接口；至少保证该文件位于 Git 忽略目录。
        }
    }

    /** 读取必填字段，缺失时让整个旧会话失效，避免构造残缺 SDK 会话。 */
    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing iLink session field: " + key);
        }
        return value;
    }
}
