package com.example.ykdsummer.fashion.runtime;

import com.example.ykdsummer.ai.tool.ImageTaskRunner;
import com.example.ykdsummer.fashion.application.FashionWardrobeIngestionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable cutout tasks and uses the existing bounded image worker pool for parallel execution. */
@Component
@ConditionalOnBean(FashionWardrobeIngestionService.class)
public class FashionGarmentCutoutDispatcher {
    private final FashionWardrobeIngestionService ingestion;
    private final ImageTaskRunner workers;

    public FashionGarmentCutoutDispatcher(FashionWardrobeIngestionService ingestion, ImageTaskRunner workers) {
        this.ingestion = ingestion;
        this.workers = workers;
    }

    @Scheduled(fixedDelayString = "${app.fashion.ingestion.dispatch-interval:3s}")
    void dispatch() {
        ingestion.expireUnconfirmedDrafts();
        for (String taskId : ingestion.pendingCutoutTaskIds(16)) {
            if (!workers.submit(() -> ingestion.executeCutoutTask(taskId))) return;
        }
    }
}
