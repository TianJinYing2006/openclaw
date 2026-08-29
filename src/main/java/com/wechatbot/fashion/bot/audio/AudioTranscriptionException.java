package com.wechatbot.fashion.bot.audio;

/** ASR 调用失败。上层会记录脱敏日志并降级为只分析视频画面。 */
public class AudioTranscriptionException extends RuntimeException {

    public AudioTranscriptionException(String message) {
        super(message);
    }

    public AudioTranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
