package com.example.ykdsummer.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bounded local worker settings for image generation and image revision tasks. */
@Component
@ConfigurationProperties(prefix = "app.ai.image-background")
public class ImageTaskExecutionProperties {

    private int workerThreads = 2;
    private int queueCapacity = 10;

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = Math.max(1, workerThreads);
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = Math.max(1, queueCapacity);
    }
}
