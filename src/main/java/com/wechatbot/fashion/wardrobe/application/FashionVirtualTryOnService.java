package com.wechatbot.fashion.wardrobe.application;

import com.wechatbot.fashion.ai.service.LocalImageAssetStore;
import com.wechatbot.fashion.ai.service.LocalImageAssetStore.StoredImage;
import com.wechatbot.fashion.ai.service.OssImageAssetStore;
import com.wechatbot.fashion.wardrobe.config.FashionTryOnProperties;
import com.wechatbot.fashion.wardrobe.domain.FashionTryOnTask;
import com.wechatbot.fashion.wardrobe.domain.FashionTryOnWork;
import com.wechatbot.fashion.wardrobe.persistence.FashionTryOnRepository;
import com.wechatbot.fashion.wardrobe.runtime.FashionTryOnCompletedEvent;
import com.wechatbot.fashion.wardrobe.runtime.FashionTryOnFailedEvent;
import com.wechatbot.fashion.persistence.ImageAssetMetadataStore;
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

    /**
     * Submits a try-on task whose garment is an external reference outfit image. The caller
     * supplies the downloaded garment bytes; this service persists the image as a user-owned
     * asset before delegating to the repository so the existing claim/render pipeline is reused.
     */
    public FashionTryOnTask submitWithReferenceOutfit(String externalUserId, String referenceOutfitId,
                                                      byte[] garmentBytes, String garmentCategoryCode) {
        if (garmentBytes == null || garmentBytes.length == 0) {
            throw new IllegalArgumentException("试穿参考单品图内容为空");
        }
        StoredImage garment = imageStore.saveGenerated(externalUserId,
                "fashion-tryon-reference:" + referenceOutfitId, garmentBytes, null);
        assetMetadata.record(externalUserId, garment, imageStore instanceof OssImageAssetStore ? "oss" : "local");
        return tasks.submitWithReferenceOutfit(externalUserId, referenceOutfitId,
                garment.assetId(), garment.version(), garmentCategoryCode);
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
                publishFailure(work, result.failureSummary());
                return;
            }
            StoredImage output = imageStore.saveGenerated(work.externalUserId(), "fashion-tryon:" + work.task().id(),
                    result.imageBytes(), result.remoteUrl());
            assetMetadata.record(work.externalUserId(), output, imageStore instanceof OssImageAssetStore ? "oss" : "local");
            tasks.succeed(work.task().id(), output.assetId(), output.version(), Instant.now());
            publish(work, output, result.imageBytes());
            Long wardrobeItemId = work.task().wardrobeItemId();
            log.info("Fashion virtual try-on completed, task={}, wardrobeItem={}, source={}", work.task().id(),
                    wardrobeItemId == null ? "reference" : wardrobeItemId, work.task().garmentSource());
        } catch (RuntimeException exception) {
            try {
                tasks.fail(work.task().id(), "试衣生成或图片存储失败，请稍后重试", Instant.now());
            } catch (RuntimeException ignored) {
                // Preserve the original exception as the useful diagnostic signal.
            }
            log.warn("Fashion virtual try-on failed, task={}", work.task().id(), exception);
            publishFailure(work, "试衣生成或图片存储失败，请稍后重试");
        }
    }

    private StoredImage find(String externalUserId, com.wechatbot.fashion.wardrobe.domain.FashionImageAsset asset) {
        return imageStore.find(externalUserId, asset.assetId(), asset.version())
                .orElseThrow(() -> new IllegalStateException("试衣素材图片已不可用"));
    }

    private void publish(FashionTryOnWork work, StoredImage output, byte[] imageBytes) {
        try {
            Long wardrobeItemId = work.task().wardrobeItemId();
            completionPublisher.publishEvent(new FashionTryOnCompletedEvent(work.externalUserId(), work.task().id(),
                    wardrobeItemId == null ? 0L : wardrobeItemId, imageBytes, output.assetId(), output.version()));
        } catch (RuntimeException exception) {
            // Output and task are already durable; reply-context recovery can still deliver a later resend request.
            log.warn("Could not publish fashion virtual try-on completion, task={}", work.task().id(), exception);
        }
    }

    /** 试衣失败时发布通知事件，避免用户只收到"开始生成"后毫无下文。 */
    private void publishFailure(FashionTryOnWork work, String reason) {
        try {
            completionPublisher.publishEvent(new FashionTryOnFailedEvent(work.externalUserId(), work.task().id(), reason));
        } catch (RuntimeException exception) {
            log.warn("Could not publish fashion virtual try-on failure, task={}", work.task().id(), exception);
        }
    }
}
