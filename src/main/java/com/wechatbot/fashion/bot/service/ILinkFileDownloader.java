package com.wechatbot.fashion.bot.service;

import com.wechatbot.fashion.ai.model.AiFile;
import com.wechatbot.fashion.ai.model.AiFileMediaTypes;
import com.wechatbot.fashion.bot.message.ILinkMessageType;
import io.github.morningwn.client.ILinkClient;
import io.github.morningwn.protocol.CDNMedia;
import io.github.morningwn.protocol.FileItem;
import io.github.morningwn.protocol.MessageItem;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 从腾讯 CDN 下载并解密微信文件，再包装为 Responses 当前轮次的文件输入。 */
@Service
public class ILinkFileDownloader {

    static final int MAX_FILES = 3;
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;

    private volatile ILinkClient client;

    public void attach(ILinkClient client) {
        this.client = client;
    }

    public void detach(ILinkClient expectedClient) {
        if (client == expectedClient) {
            client = null;
        }
    }

    public List<AiFile> downloadFiles(List<MessageItem> items) {
        return downloadFiles(client, items);
    }

    /** Uses an explicitly owned client for a managed bot instance. */
    public List<AiFile> downloadFiles(ILinkClient currentClient, List<MessageItem> items) {
        List<FileItem> fileItems = items.stream()
                .filter(item -> item != null && ILinkMessageType.from(item.type()) == ILinkMessageType.FILE)
                .map(MessageItem::fileItem)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (fileItems.size() > MAX_FILES) {
            throw new FileProcessingException("一次最多发送 3 个文件");
        }

        if (currentClient == null) {
            throw new FileProcessingException("文件读取服务尚未连接，请稍后重试");
        }

        long totalBytes = 0;
        List<AiFile> result = new ArrayList<>(fileItems.size());
        for (FileItem file : fileItems) {
            String fileName = normalizeFileName(file.fileName());
            String mediaType = AiFileMediaTypes.forFileName(fileName)
                    .orElseThrow(() -> new FileProcessingException("暂不支持这个文件格式：" + fileName));
            rejectOversizedMetadata(file.len());
            CDNMedia media = file.media();
            if (media == null) {
                throw new FileProcessingException("文件读取失败，请重新发送");
            }

            byte[] bytes;
            try {
                bytes = currentClient.downloadAndDecryptMedia(media, null);
            } catch (RuntimeException exception) {
                throw new FileProcessingException("文件读取失败，请重新发送", exception);
            }
            if (bytes == null || bytes.length == 0) {
                throw new FileProcessingException("文件内容为空，请重新发送");
            }
            if (bytes.length > MAX_FILE_BYTES) {
                throw new FileProcessingException("单个文件不能超过 20 MiB");
            }
            totalBytes += bytes.length;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new FileProcessingException("文件总大小不能超过 20 MiB");
            }
            verifyMd5WhenPresent(file.md5(), bytes);
            result.add(new AiFile(fileName, mediaType, bytes));
        }
        return List.copyOf(result);
    }

    private static String normalizeFileName(String rawFileName) {
        if (rawFileName == null || rawFileName.isBlank()) {
            throw new FileProcessingException("文件名为空，请重新发送");
        }
        String normalized = rawFileName.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new FileProcessingException("文件名无效，请重命名后发送");
        }
        return normalized;
    }

    private static void rejectOversizedMetadata(String length) {
        if (length == null || length.isBlank()) {
            return;
        }
        try {
            if (Long.parseLong(length) > MAX_FILE_BYTES) {
                throw new FileProcessingException("单个文件不能超过 20 MiB");
            }
        } catch (NumberFormatException ignored) {
            // iLink 的 len 是字符串；无法解析时仍以下载后的真实 byte[] 长度为准。
        }
    }

    private static void verifyMd5WhenPresent(String expected, byte[] bytes) {
        if (expected == null || !expected.matches("(?i)[0-9a-f]{32}")) {
            return;
        }
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
            if (!actual.equalsIgnoreCase(expected)) {
                throw new FileProcessingException("文件校验失败，请重新发送");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide MD5", impossible);
        }
    }

    public static class FileProcessingException extends RuntimeException {

        private final String userMessage;

        FileProcessingException(String userMessage) {
            super(userMessage);
            this.userMessage = userMessage;
        }

        FileProcessingException(String userMessage, Throwable cause) {
            super(userMessage, cause);
            this.userMessage = userMessage;
        }

        public String userMessage() {
            return userMessage;
        }
    }
}
