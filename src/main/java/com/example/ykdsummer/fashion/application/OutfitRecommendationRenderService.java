package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.ai.service.OssImageAssetStore;
import com.example.ykdsummer.fashion.config.OutfitRecommendationProperties;
import com.example.ykdsummer.fashion.domain.OutfitRenderStatus;
import com.example.ykdsummer.fashion.domain.OutfitRenderWork;
import com.example.ykdsummer.fashion.persistence.OutfitRecommendationRepository;
import com.example.ykdsummer.fashion.runtime.FashionOutfitRecommendationCompletedEvent;
import com.example.ykdsummer.persistence.ImageAssetMetadataStore;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Executes one durable option render and falls back to a real-pixel outfit board on provider failure. */
@Service
@ConditionalOnBean(OutfitRecommendationRepository.class)
@ConditionalOnProperty(prefix = "app.fashion.outfit-recommendation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OutfitRecommendationRenderService {
    private static final Logger log = LoggerFactory.getLogger(OutfitRecommendationRenderService.class);
    private final OutfitRecommendationRepository recommendations;
    private final OutfitRenderService renderer;
    private final LocalImageAssetStore imageStore;
    private final OutfitRecommendationProperties properties;
    private volatile ImageAssetMetadataStore assetMetadata = ImageAssetMetadataStore.disabled();
    private volatile ApplicationEventPublisher completionPublisher = event -> { };

    public OutfitRecommendationRenderService(
            OutfitRecommendationRepository recommendations,
            OutfitRenderService renderer,
            LocalImageAssetStore imageStore,
            OutfitRecommendationProperties properties
    ) {
        this.recommendations = recommendations;
        this.renderer = renderer;
        this.imageStore = imageStore;
        this.properties = properties;
    }

    @Autowired(required = false)
    void setAssetMetadata(ImageAssetMetadataStore assetMetadata) {
        this.assetMetadata = assetMetadata == null ? ImageAssetMetadataStore.disabled() : assetMetadata;
    }

    @Autowired(required = false)
    void setCompletionPublisher(ApplicationEventPublisher completionPublisher) {
        this.completionPublisher = completionPublisher == null ? event -> { } : completionPublisher;
    }

    public List<String> pendingOptionIds(int limit) {
        return recommendations.pendingRenderOptionIds(limit);
    }

    public int recoverInterruptedRenders() {
        return recommendations.recoverInterruptedRenders();
    }

    public void execute(String optionId) {
        Optional<OutfitRenderWork> claimed = recommendations.claimRender(optionId, Instant.now());
        if (claimed.isEmpty()) return;
        OutfitRenderWork work = claimed.orElseThrow();
        try {
            List<ResolvedSource> sources = resolve(work);
            OutfitRenderService.RenderResult generated;
            try {
                generated = renderer.render(work.externalUserId(),
                        sources.stream().map(value -> new OutfitRenderService.Source(
                                value.role(), value.displayName(), value.image())).toList(),
                        properties.getProviderTimeout());
            } catch (RuntimeException providerFailure) {
                log.warn("Outfit image provider failed, composing real wardrobe board, option={}",
                        work.optionId(), providerFailure);
                generated = OutfitRenderService.RenderResult.failed("搭配效果图服务暂时不可用");
            }
            byte[] outputBytes;
            String remoteUrl;
            OutfitRenderStatus status;
            if (generated.hasImage() && isDecodableImage(generated.imageBytes())) {
                outputBytes = generated.imageBytes();
                remoteUrl = generated.remoteUrl();
                status = OutfitRenderStatus.SUCCEEDED;
            } else {
                outputBytes = OutfitBoardComposer.compose(sources.stream()
                        .map(value -> new OutfitBoardComposer.Source(value.role(),
                                imageStore.readBytes(value.image()))).toList(), work.rank());
                remoteUrl = null;
                status = OutfitRenderStatus.FALLBACK;
            }
            StoredImage output = imageStore.saveGenerated(work.externalUserId(),
                    "fashion-outfit:" + work.optionId(), outputBytes, remoteUrl);
            assetMetadata.record(work.externalUserId(), output,
                    imageStore instanceof OssImageAssetStore ? "oss" : "local");
            recommendations.completeRender(work.optionId(), output.assetId(), output.version(), status, Instant.now());
            publish(work, output, outputBytes, status);
            log.info("Fashion outfit board completed, option={}, rank={}, mode={}",
                    work.optionId(), work.rank(), status);
        } catch (RuntimeException failure) {
            try {
                recommendations.failRender(work.optionId(),
                        "搭配效果图生成和真实单品拼图均失败，请稍后重试", Instant.now());
            } catch (RuntimeException ignored) {
                // Preserve the first exception for diagnostics.
            }
            log.warn("Fashion outfit board failed, option={}", work.optionId(), failure);
        }
    }

    private List<ResolvedSource> resolve(OutfitRenderWork work) {
        List<ResolvedSource> values = new ArrayList<>();
        for (OutfitRenderWork.SourceItem item : work.items()) {
            StoredImage image = imageStore.find(work.externalUserId(), item.image().assetId(), item.image().version())
                    .orElseThrow(() -> new IllegalStateException("搭配单品图片已不可用"));
            values.add(new ResolvedSource(item.role(), item.displayName(), image));
        }
        if (values.size() < 2) throw new IllegalStateException("搭配方案没有足够的单品图片");
        return List.copyOf(values);
    }

    private void publish(
            OutfitRenderWork work,
            StoredImage output,
            byte[] bytes,
            OutfitRenderStatus status
    ) {
        try {
            completionPublisher.publishEvent(new FashionOutfitRecommendationCompletedEvent(
                    work.externalUserId(), work.recommendationId(), work.optionId(), work.rank(),
                    work.displaySummary(), status, bytes, output.assetId(), output.version()));
        } catch (RuntimeException failure) {
            log.warn("Could not publish completed outfit board, option={}", work.optionId(), failure);
        }
    }

    private static boolean isDecodableImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes)) != null;
        } catch (java.io.IOException ignored) {
            return false;
        }
    }

    private record ResolvedSource(String role, String displayName, StoredImage image) { }
}
