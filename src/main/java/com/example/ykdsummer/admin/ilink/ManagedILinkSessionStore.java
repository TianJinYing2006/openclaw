package com.example.ykdsummer.admin.ilink;

import com.example.ykdsummer.admin.service.AdminPlatformService;
import com.example.ykdsummer.admin.service.SessionCipher;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import io.github.morningwn.client.ILinkAuthSession;
import io.github.morningwn.handler.SessionHandler;
import io.github.morningwn.protocol.QrCodeResponse;
import io.github.morningwn.protocol.WeixinMessage;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Database-backed, encrypted session handler for exactly one managed iLink instance. */
final class ManagedILinkSessionStore implements SessionHandler {
    private static final Logger log = LoggerFactory.getLogger(ManagedILinkSessionStore.class);
    private final String instanceId;
    private final AdminPlatformService platform;
    private final SessionCipher cipher;
    private final ILinkRuntimeState runtimeState;
    private ILinkAuthSession currentSession;
    private String cursor = "";

    ManagedILinkSessionStore(String instanceId, AdminPlatformService platform, SessionCipher cipher,
                             ILinkRuntimeState runtimeState) {
        this.instanceId = instanceId;
        this.platform = platform;
        this.cipher = cipher;
        this.runtimeState = runtimeState;
    }

    @Override
    public synchronized ILinkAuthSession loadSession() {
        return platform.encryptedSession(instanceId).map(encrypted -> {
            try {
                Properties values = read(cipher.decrypt(encrypted.ciphertext(), encrypted.iv()));
                currentSession = new ILinkAuthSession(required(values, "token"), required(values, "baseUrl"),
                        required(values, "accountId"), required(values, "userId"));
                cursor = values.getProperty("cursor", "");
                runtimeState.authenticated(currentSession.accountId());
                // The runner has just started from a durable authenticated session. Keep MySQL aligned with it.
                platform.markSessionRestored(instanceId, currentSession.accountId());
                return currentSession;
            } catch (RuntimeException exception) {
                runtimeState.failed("无法读取加密 iLink 会话");
                platform.updateConnection(instanceId, "ERROR", "无法读取加密 iLink 会话");
                log.warn("Cannot restore managed iLink session, instance={}", instanceId, exception);
                return null;
            }
        }).orElse(null);
    }

    @Override
    public synchronized void persistSession(ILinkAuthSession session) {
        currentSession = session;
        platform.saveSession(instanceId, cipher.encrypt(serialize()), session.accountId());
        runtimeState.authenticated(session.accountId());
    }

    @Override
    public synchronized void clearSession(ILinkAuthSession expiredSession) {
        currentSession = null;
        cursor = "";
        platform.clearSession(instanceId);
        runtimeState.starting();
    }

    @Override
    public synchronized void onQrcode(QrCodeResponse response) {
        String url = response == null ? "" : response.qrcodeImgContent();
        runtimeState.waitingForQrCode(url);
        platform.updateConnection(instanceId, "WAITING_FOR_QR_SCAN", "");
    }

    @Override
    public synchronized String confirmGetUpdatesBuf(String currentGetUpdatesBuf, String suggestedGetUpdatesBuf,
                                                     List<WeixinMessage> messages, boolean fullyProcessed) {
        if (!fullyProcessed || suggestedGetUpdatesBuf == null || suggestedGetUpdatesBuf.isBlank()) {
            return currentGetUpdatesBuf;
        }
        cursor = suggestedGetUpdatesBuf;
        if (currentSession != null) {
            platform.updateEncryptedSession(instanceId, cipher.encrypt(serialize()), currentSession.accountId());
        }
        return suggestedGetUpdatesBuf;
    }

    synchronized String loadCursor() { return cursor; }

    private String serialize() {
        if (currentSession == null) return "";
        Properties values = new Properties();
        values.setProperty("token", currentSession.token());
        values.setProperty("baseUrl", currentSession.baseUrl());
        values.setProperty("accountId", currentSession.accountId());
        values.setProperty("userId", currentSession.userId());
        values.setProperty("cursor", cursor == null ? "" : cursor);
        try (StringWriter writer = new StringWriter()) {
            values.store(writer, "Encrypted managed iLink session");
            return writer.toString();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not serialize iLink session", impossible);
        }
    }

    private static Properties read(String content) {
        Properties values = new Properties();
        try (StringReader reader = new StringReader(content == null ? "" : content)) {
            values.load(reader);
            return values;
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not parse iLink session", impossible);
        }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing iLink session field: " + key);
        return value;
    }
}
