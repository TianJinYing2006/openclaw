package com.example.ykdsummer.fashion.runtime;

import com.example.ykdsummer.ai.tool.ImageTaskRunner;
import com.example.ykdsummer.fashion.application.OutfitRecommendationRenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers interrupted outfit renders and dispatches them through the existing bounded image pool. */
@Component
@ConditionalOnBean(OutfitRecommendationRenderService.class)
public class OutfitRecommendationRenderDispatcher implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OutfitRecommendationRenderDispatcher.class);
    private final OutfitRecommendationRenderService renders;
    private final ImageTaskRunner workers;

    public OutfitRecommendationRenderDispatcher(
            OutfitRecommendationRenderService renders,
            ImageTaskRunner workers
    ) {
        this.renders = renders;
        this.workers = workers;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        int recovered = renders.recoverInterruptedRenders();
        if (recovered > 0) log.info("Recovered {} interrupted outfit recommendation render(s)", recovered);
    }

    @Scheduled(fixedDelayString = "${app.fashion.outfit-recommendation.dispatch-interval:3s}")
    void dispatch() {
        for (String optionId : renders.pendingOptionIds(6)) {
            if (!workers.submit(() -> renders.execute(optionId))) return;
        }
    }
}
