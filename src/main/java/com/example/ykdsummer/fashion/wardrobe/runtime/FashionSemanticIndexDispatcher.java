package com.example.ykdsummer.fashion.wardrobe.runtime;

import com.example.ykdsummer.fashion.wardrobe.application.FashionSemanticIndexService;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionSemanticIndexJob;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(FashionSemanticIndexService.class)
public class FashionSemanticIndexDispatcher {
    private static final Logger log = LoggerFactory.getLogger(FashionSemanticIndexDispatcher.class);

    private final FashionSemanticIndexService indexing;
    private final ThreadPoolTaskExecutor executor;
    private final AtomicBoolean applicationReady = new AtomicBoolean();

    public FashionSemanticIndexDispatcher(
            FashionSemanticIndexService indexing,
            @Qualifier("fashionSemanticExecutor") ThreadPoolTaskExecutor executor
    ) {
        this.indexing = indexing;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    void applicationReady() {
        applicationReady.set(true);
    }

    @Scheduled(fixedDelayString = "${app.fashion.semantic.dispatch-interval:5s}")
    void dispatch() {
        if (!applicationReady.get()) return;
        for (FashionSemanticIndexJob job : indexing.claimWork()) {
            try {
                executor.execute(() -> indexing.execute(job));
            } catch (RuntimeException rejected) {
                log.warn("Fashion semantic worker queue is full; job {} will resume after its lease", job.id());
                return;
            }
        }
    }
}
