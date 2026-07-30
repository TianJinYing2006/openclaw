package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.ai.service.OssImageAssetStore;
import com.example.ykdsummer.fashion.config.FashionTryOnProperties;
import com.example.ykdsummer.fashion.domain.FashionTryOnTask;
import com.example.ykdsummer.fashion.domain.FashionTryOnWork;
import com.example.ykdsummer.fashion.persistence.FashionTryOnRepository;
import com.example.ykdsummer.fashion.runtime.FashionTryOnCompletedEvent;
import com.example.ykdsummer.persistence.ImageAssetMetadataStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Coordinates durable task submission, two-image rendering, asset persistence, and completion publication. */
@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class FashionVirtualTryOnService {
    private static final Logger log = LoggerFactory.getLogger(FashionVirtualTryOnService.class);
    private final FashionTryOnRepository tasks;
    private final VirtualTryOnService renderer;
    private final LocalImageAssetStore imageStore;
    private final FashionTryOnProperties properties;
    private volatile ImageAssetMetadataStore assetMetadata = ImageAssetMetadataStore.disabled();
    private volatile ApplicationEventPublisher completionPublisher = event -> { };

    public FashionVirtualTryOnService(FashionTryOnRepository tasks, VirtualTryOnService renderer,
                                      LocalImageAssetStore imageStore, FashionTryOnProperties properties) {
        this.tasks = tasks;
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

    /** The request thread only writes durable state; rendering happens in the bounded background worker. */
    public FashionTryOnTask submit(String externalUserId, long wardrobeItemId) {
        return tasks.submit(externalUserId, wardrobeItemId);
    }

    public List<String> pendingTaskIds(int limit) { return tasks.pendingTaskIds(limit); }
    public Optional<FashionTryOnTask> latest(String externalUserId) { return tasks.latest(externalUserId); }
    public int recoverInterruptedTasks() { return tasks.recoverInterruptedTasks(); }

    /** Claiming is atomic, so duplicate scheduler ticks and queue retries cannot render the same open task twice. */
    public void execute(String taskId) {
        Optional<FashionTryOnWork> claimed = tasks.claim(taskId, Instant.now());
        if (claimed.isEmpty()) return;
        FashionTryOnWork work = claimed.orElseThrow();
        try {
            StoredImage person = find(work.externalUserId(), work.personImage());
            StoredImage garment = find(work.externalUserId(), work.garmentImage());
            VirtualTryOnService.TryOnResult result = renderer.render(work.externalUserId(), person, garment,
                    work.garmentCategoryCode(), properties.getProviderTimeout());
            if (!result.hasImage()) {
                tasks.fail(work.task().id(), result.failureSummary(), Instant.now());
                return;
            }
            StoredImage output = imageStore.saveGenerated(work.externalUserId(), "fashion-tryon:" + work.task().id(),
                    result.imageBytes(), result.remoteUrl());
            assetMetadata.record(work.externalUserId(), output, imageStore instanceof OssImageAssetStore ? "oss" : "local");
            tasks.succeed(work.task().id(), output.assetId(), output.version(), Instant.now());
            publish(work, output, result.imageBytes());
            log.info("Fashion virtual try-on completed, task={}, wardrobeItem={}", work.task().id(), work.task().wardrobeItemId());
        } catch (RuntimeException exception) {
            try {
                tasks.fail(work.task().id(), "试衣生成或图片存储失败，请稍后重试", Instant.now());
            } catch (RuntimeException ignored) {
                // Preserve the original exception as the useful diagnostic signal.
            }
            log.warn("Fashion virtual try-on failed, task={}", work.task().id(), exception);
        }
    }

    private StoredImage find(String externalUserId, com.example.ykdsummer.fashion.domain.FashionImageAsset asset) {
        return imageStore.find(externalUserId, asset.assetId(), asset.version())
                .orElseThrow(() -> new IllegalStateException("试衣素材图片已不可用"));
    }

    private void publish(FashionTryOnWork work, StoredImage output, byte[] imageBytes) {
        try {
            completionPublisher.publishEvent(new FashionTryOnCompletedEvent(work.externalUserId(), work.task().id(),
                    work.task().wardrobeItemId(), imageBytes, output.assetId(), output.version()));
        } catch (RuntimeException exception) {
            // Output and task are already durable; reply-context recovery can still deliver a later resend request.
            log.warn("Could not publish fashion virtual try-on completion, task={}", work.task().id(), exception);
        }
    }
}
