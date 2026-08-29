package com.example.ykdsummer.fashion.wardrobe.runtime;

import com.example.ykdsummer.ai.tool.ImageTaskRunner;
import com.example.ykdsummer.fashion.wardrobe.application.FashionVirtualTryOnService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Restarts uncompleted work after a process restart and submits durable tasks to the existing bounded image pool. */
@Component
@ConditionalOnBean(FashionVirtualTryOnService.class)
public class FashionTryOnDispatcher implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FashionTryOnDispatcher.class);
    private final FashionVirtualTryOnService tryOn;
    private final ImageTaskRunner workers;

    public FashionTryOnDispatcher(FashionVirtualTryOnService tryOn, ImageTaskRunner workers) {
        this.tryOn = tryOn;
        this.workers = workers;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        int recovered = tryOn.recoverInterruptedTasks();
        if (recovered > 0) log.info("Recovered {} interrupted fashion virtual try-on task(s)", recovered);
    }

    @Scheduled(fixedDelayString = "${app.fashion.tryon.dispatch-interval:3s}")
    void dispatch() {
        for (String taskId : tryOn.pendingTaskIds(8)) {
            if (!workers.submit(() -> tryOn.execute(taskId))) return;
        }
    }
}
