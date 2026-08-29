package com.wechatbot.fashion.wardrobe.persistence;

import com.wechatbot.fashion.wardrobe.domain.FashionTryOnTask;
import com.wechatbot.fashion.wardrobe.domain.FashionTryOnWork;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for durable, user-scoped virtual try-on work. */
public interface FashionTryOnRepository {
    FashionTryOnTask submit(String externalUserId, long wardrobeItemId);

    /**
     * Submits a try-on task whose garment is an external reference outfit image (not a
     * user wardrobe item). The image must already be persisted as an asset owned by the user.
     */
    FashionTryOnTask submitWithReferenceOutfit(String externalUserId, String referenceOutfitId,
                                               String garmentAssetId, int garmentVersion, String garmentCategoryCode);

    List<String> pendingTaskIds(int limit);
    Optional<FashionTryOnWork> claim(String taskId, Instant now);
    void succeed(String taskId, String outputAssetId, int outputVersion, Instant completedAt);
    void fail(String taskId, String failureSummary, Instant completedAt);
    Optional<FashionTryOnTask> latest(String externalUserId);
    int recoverInterruptedTasks();
}
