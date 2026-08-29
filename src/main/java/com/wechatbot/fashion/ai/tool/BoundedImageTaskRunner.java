package com.wechatbot.fashion.ai.tool;

import com.wechatbot.fashion.ai.config.ImageTaskExecutionProperties;
import com.wechatbot.fashion.common.concurrent.GracefulExecutorShutdown;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Process-local, bounded executor for asynchronous image generation and revision work. */
@Component
public class BoundedImageTaskRunner implements ImageTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(BoundedImageTaskRunner.class);

    private final ThreadPoolExecutor executor;

    public BoundedImageTaskRunner(ImageTaskExecutionProperties properties) {
        int workers = properties.getWorkerThreads();
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "ai-image-background");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public boolean submit(Runnable task) {
        if (task == null) {
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    @PreDestroy
    void stop() {
        GracefulExecutorShutdown.shutdown("ai-image-background", Duration.ofSeconds(30), log, executor);
    }
}
