package com.wechatbot.fashion.bot.video;

/** 视频下载、校验或抽帧失败；userMessage 可以安全地显示给微信用户。 */
public class VideoProcessingException extends RuntimeException {

    private final String userMessage;

    public VideoProcessingException(String userMessage) {
        super(userMessage);
        this.userMessage = userMessage;
    }

    public VideoProcessingException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
