package com.example.ykdsummer.bot.message;

import io.github.morningwn.protocol.MessageItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 SDK {@link MessageItem} 列表中提取结构化内容。
 *
 * <p>职责单一：只做"类型判断 + 文字合并"，不发起任何网络请求。</p>
 */
@Component
public class MessageExtractor {

    /**
     * @param items SDK 消息项列表
     * @return 提取的结构化内容
     */
    public ExtractedContent extract(List<MessageItem> items) {
        List<String> textParts = new ArrayList<>();
        boolean hasImage = false;
        boolean hasFile = false;
        boolean hasVideo = false;
        boolean hasVoiceWithoutTranscript = false;
        boolean hasPlainText = false;
        boolean hasVoiceTranscript = false;

        for (MessageItem item : items == null ? List.<MessageItem>of() : items) {
            if (item == null) {
                continue;
            }
            ILinkMessageType type = ILinkMessageType.from(item.type());
            if (type == ILinkMessageType.TEXT && item.textItem() != null) {
                addNonBlank(textParts, item.textItem().text());
                hasPlainText = true;
            } else if (type == ILinkMessageType.VOICE && item.voiceItem() != null) {
                String transcript = item.voiceItem().text();
                if (transcript == null || transcript.isBlank()) {
                    hasVoiceWithoutTranscript = true;
                } else {
                    addNonBlank(textParts, transcript);
                    hasVoiceTranscript = true;
                }
            } else if (type == ILinkMessageType.IMAGE && item.imageItem() != null) {
                hasImage = true;
            } else if (type == ILinkMessageType.FILE && item.fileItem() != null) {
                hasFile = true;
            } else if (type == ILinkMessageType.VIDEO && item.videoItem() != null) {
                hasVideo = true;
            }
        }
        return new ExtractedContent(
                String.join("\n", textParts).trim(),
                hasImage,
                hasFile,
                hasVideo,
                hasVoiceWithoutTranscript,
                hasPlainText,
                hasVoiceTranscript
        );
    }

    private static void addNonBlank(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    /** 本次提取的临时结果，只在处理一条微信消息时使用。 */
    public record ExtractedContent(
            String prompt,
            boolean hasImage,
            boolean hasFile,
            boolean hasVideo,
            boolean hasVoiceWithoutTranscript,
            boolean hasPlainText,
            boolean hasVoiceTranscript
    ) {
        public boolean isPlainTextOnly() {
            return hasPlainText && !hasVoiceTranscript && !hasImage && !hasFile && !hasVideo;
        }
    }
}
