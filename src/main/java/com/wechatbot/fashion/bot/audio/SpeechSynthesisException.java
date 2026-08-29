package com.wechatbot.fashion.bot.audio;

/** 语音合成失败时抛出；回复层捕获后保留原有文字答案。 */
public class SpeechSynthesisException extends RuntimeException {
    private static final String DEFAULT_USER_MESSAGE = "语音文件暂时无法生成，请稍后重试。";

    private final String userMessage;

    public SpeechSynthesisException(String message) {
        this(message, DEFAULT_USER_MESSAGE, null);
    }

    public SpeechSynthesisException(String message, Throwable cause) {
        this(message, DEFAULT_USER_MESSAGE, cause);
    }

    public SpeechSynthesisException(String message, String userMessage, Throwable cause) {
        super(message, cause);
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
