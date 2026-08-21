package com.example.ykdsummer.fashion.wardrobe.runtime;

import com.example.ykdsummer.ai.tool.ImageTaskRunner;
import com.example.ykdsummer.fashion.wardrobe.application.FashionWardrobeIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable cutout tasks, uses the existing bounded image worker pool, and recovers interrupted tasks after restart. */
@Component
@ConditionalOnBean(FashionWardrobeIngestionService.class)
public class FashionGarmentCutoutDispatcher implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FashionGarmentCutoutDispatcher.class);
    private final FashionWardrobeIngestionService ingestion;
    private final ImageTaskRunner workers;

    public FashionGarmentCutoutDispatcher(FashionWardrobeIngestionService ingestion, ImageTaskRunner workers) {
        this.ingestion = ingestion;
        this.workers = workers;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        int recovered = ingestion.recoverInterruptedCutoutTasks();
        if (recovered > 0) log.info("Recovered {} interrupted garment cutout task(s)", recovered);
    }

    @Scheduled(fixedDelayString = "${app.fashion.ingestion.dispatch-interval:3s}")
    void dispatch() {
        ingestion.expireUnconfirmedDrafts();
        for (String taskId : ingestion.pendingCutoutTaskIds(16)) {
            if (!workers.submit(() -> ingestion.executeCutoutTask(taskId))) return;
        }
    }
}
