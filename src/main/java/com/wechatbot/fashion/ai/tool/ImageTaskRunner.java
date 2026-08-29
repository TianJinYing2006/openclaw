package com.wechatbot.fashion.ai.tool;

/** Runs long image work outside the model request that created it. */
public interface ImageTaskRunner {

    boolean submit(Runnable task);

    static ImageTaskRunner inline() {
        return task -> {
            task.run();
            return true;
        };
    }
}
