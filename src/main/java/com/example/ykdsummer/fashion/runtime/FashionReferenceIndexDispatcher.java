package com.example.ykdsummer.fashion.runtime;

import com.example.ykdsummer.fashion.application.FashionReferenceIndexService;
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
@ConditionalOnBean(FashionReferenceIndexService.class)
public class FashionReferenceIndexDispatcher {
    private static final Logger log = LoggerFactory.getLogger(FashionReferenceIndexDispatcher.class);
    private final FashionReferenceIndexService indexing;
    private final ThreadPoolTaskExecutor executor;
    private final AtomicBoolean ready = new AtomicBoolean();

    public FashionReferenceIndexDispatcher(FashionReferenceIndexService indexing,
            @Qualifier("fashionSemanticExecutor") ThreadPoolTaskExecutor executor) {
        this.indexing = indexing;
        this.executor = executor;
    }
    @EventListener(ApplicationReadyEvent.class) void ready() { ready.set(true); }
    @Scheduled(fixedDelayString = "${app.fashion.semantic.dispatch-interval:5s}")
    void dispatch() {
        if (!ready.get()) return;
        indexing.claimWork().forEach(job -> {
            try { executor.execute(() -> indexing.execute(job)); }
            catch (RuntimeException rejected) { log.warn("Fashion reference semantic queue is full"); }
        });
    }
}
