package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.bot.config.LongTextOutputProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 在微信回复过长时临时保存完整内容，等待用户选择全文或 TXT。 */
@Service
public class LongTextOutputService {
    private final LongTextOutputProperties properties;
    private final Cache<String, String> pendingTexts;

    public LongTextOutputService(LongTextOutputProperties properties) {
        this.properties = properties;
        this.pendingTexts = Caffeine.newBuilder()
                .maximumSize(properties.getMaxPendingUsers())
                .expireAfterWrite(properties.getPendingTtl())
                .build();
    }

    public Optional<String> offer(String userId, String text) {
        String safeText = safe(text);
        if (!properties.isEnabled() || codePointCount(safeText) <= properties.getThresholdCharacters()) {
            return Optional.empty();
        }
        pendingTexts.put(userKey(userId), safeText);
        return Optional.of("本次结果约 " + codePointCount(safeText)
                + " 字，直接发送不便查看。回复“全文”直接发文本，或回复“TXT”获取文本文件。");
    }

    public Optional<Delivery> consumeSelection(String userId, String input) {
        Selection selection = selection(input);
        if (selection == null) {
            return Optional.empty();
        }
        String text = pendingTexts.asMap().remove(userKey(userId));
        if (text == null) {
            return Optional.of(Delivery.text("没有待发送的长结果，可能已过期。请重新发起查询。"));
        }
        return Optional.of(selection == Selection.TEXT
                ? Delivery.text(text)
                : Delivery.txt("查询结果.txt", text.getBytes(StandardCharsets.UTF_8), "已按 TXT 文件发送完整结果。"));
    }

    private static Selection selection(String input) {
        return switch (safe(input).toLowerCase(Locale.ROOT)) {
            case "1", "全文", "文本", "直接发", "直接发送", "发全文", "发文本" -> Selection.TEXT;
            case "2", "txt", "文件", "文本文件", "发txt" -> Selection.TXT;
            default -> null;
        };
    }

    private static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String userKey(String userId) {
        String user = safe(userId);
        return user.isBlank() ? "unknown" : user;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private enum Selection { TEXT, TXT }

    public record Delivery(String text, String fileName, byte[] bytes, String followUpText) {
        public Delivery {
            text = safe(text);
            fileName = safe(fileName);
            bytes = bytes == null ? null : bytes.clone();
            followUpText = safe(followUpText);
        }
        public static Delivery text(String value) { return new Delivery(value, "", null, ""); }
        public static Delivery txt(String fileName, byte[] bytes, String followUpText) {
            return new Delivery("", fileName, bytes, followUpText);
        }
        public boolean hasFile() { return !fileName.isBlank() && bytes != null; }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }
}
