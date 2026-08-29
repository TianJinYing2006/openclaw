package com.wechatbot.fashion.ai.tool;

/** 隔离图片 Tool 与具体消息渠道，测试或非微信入口可使用 no-op 实现。 */
@FunctionalInterface
public interface ImageTaskCompletionPublisher {

    void publish(ImageTaskCompletionEvent event);

    static ImageTaskCompletionPublisher noOp() {
        return event -> { };
    }
}
