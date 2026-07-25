package com.example.ykdsummer.persistence;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** A Java executor cannot resume an in-memory image request after a restart, so expose it as retryable failure. */
@Component
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
@Order(10)
public class ImageTaskRecovery implements ApplicationRunner {
    private final ImageTaskPersistence tasks;

    public ImageTaskRecovery(ImageTaskPersistence tasks) {
        this.tasks = tasks;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        tasks.markInterruptedTasksFailed();
    }
}
