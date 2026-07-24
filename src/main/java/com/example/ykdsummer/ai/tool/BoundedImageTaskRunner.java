package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.config.ImageTaskExecutionProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Process-local, bounded executor for asynchronous image generation and revision work. */
@Component
public class BoundedImageTaskRunner implements ImageTaskRunner {

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
        executor.shutdownNow();
    }
}
