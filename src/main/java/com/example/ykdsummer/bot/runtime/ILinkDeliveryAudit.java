package com.example.ykdsummer.bot.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * iLink 出站媒体的最小审计日志。
 *
 * <p>SDK 成功返回只代表 iLink 网关接受了发送请求，不能证明手机已经展示附件。这里故意只记录
 * 时间、脱敏后的消息/用户标识、附件类别与大小、网关结果和异常摘要；不记录正文、文件内容、
 * contextToken 或密钥。这样后续可以定位到底失败在本地、SDK 上传还是最终 sendmessage。</p>
 */
@Component
public class ILinkDeliveryAudit {
    private static final Logger log = LoggerFactory.getLogger(ILinkDeliveryAudit.class);

    private final Path auditFile;
    private final Object writeLock = new Object();

    public ILinkDeliveryAudit() {
        this(Path.of(".ai-assets", "delivery", "events.log"));
    }

    ILinkDeliveryAudit(Path auditFile) {
        this.auditFile = auditFile.toAbsolutePath().normalize();
    }

    /** iLink SDK 已完成 CDN 上传并收到 sendmessage 成功响应。 */
    public void gatewayAccepted(String messageId, String userId, String attachmentType, int byteCount) {
        append("GATEWAY_ACCEPTED", messageId, userId, attachmentType, byteCount, "");
    }

    /** 文件上传或 sendmessage 抛出异常；保存异常类型和经脱敏/截断后的原因。 */
    public void failed(String messageId, String userId, String attachmentType, int byteCount,
                       RuntimeException exception) {
        String detail = exception == null ? "unknown" : exception.getClass().getSimpleName()
                + ":" + safe(exception.getMessage());
        append("FAILED", messageId, userId, attachmentType, byteCount, detail);
    }

    /** 原附件失败后，纯文字降级提示被 iLink 网关接受。 */
    public void fallbackAccepted(String messageId, String userId, String attachmentType) {
        append("FALLBACK_ACCEPTED", messageId, userId, attachmentType, 0, "");
    }

    private void append(String outcome, String messageId, String userId, String attachmentType,
                        int byteCount, String detail) {
        String line = Instant.now()
                + " outcome=" + safe(outcome)
                + " message=" + anonymous(messageId)
                + " user=" + anonymous(userId)
                + " type=" + safe(attachmentType)
                + " bytes=" + Math.max(0, byteCount)
                + (detail == null || detail.isBlank() ? "" : " detail=" + detail)
                + System.lineSeparator();
        try {
            synchronized (writeLock) {
                Files.createDirectories(auditFile.getParent());
                Files.writeString(auditFile, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            }
        } catch (IOException exception) {
            // 审计失败不能阻断机器人给用户的正常回复。
            log.warn("Could not append iLink delivery audit", exception);
        }
    }

    private static String anonymous(String value) {
        return Integer.toHexString((value == null ? "unknown" : value).hashCode());
    }

    private static String safe(String value) {
        String sanitized = value == null ? "" : value
                .replaceAll("(?i)bearer\\s+[^\\s]+", "Bearer[redacted]")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replace('\u0000', ' ')
                .strip();
        return sanitized.length() <= 180 ? sanitized : sanitized.substring(0, 180) + "…";
    }
}
