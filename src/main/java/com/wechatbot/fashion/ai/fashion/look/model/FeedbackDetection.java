package com.wechatbot.fashion.ai.fashion.look.model;

/** 反馈检测结果：是否为对上一次推荐的反馈，以及情感倾向（POSITIVE/NEGATIVE/MIXED）。 */
public record FeedbackDetection(boolean isFeedback, String sentiment) {

    public static FeedbackDetection notFeedback() {
        return new FeedbackDetection(false, "NONE");
    }
}
