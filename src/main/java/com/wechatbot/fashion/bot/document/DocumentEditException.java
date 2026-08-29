package com.wechatbot.fashion.bot.document;

/** 文件解析或渲染的可控异常；userMessage 可以安全地直接回复给微信用户。 */
public class DocumentEditException extends RuntimeException {

    private final String userMessage;

    public DocumentEditException(String message, String userMessage) {
        super(message);
        this.userMessage = userMessage;
    }

    public DocumentEditException(String message, String userMessage, Throwable cause) {
        super(message, cause);
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
