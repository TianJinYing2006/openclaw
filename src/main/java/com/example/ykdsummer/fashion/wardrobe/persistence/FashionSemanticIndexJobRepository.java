package com.example.ykdsummer.fashion.wardrobe.persistence;

import com.example.ykdsummer.fashion.wardrobe.domain.FashionSemanticIndexJob;
import java.time.Duration;
import java.util.List;

/** Durable queue used to decouple wardrobe writes from external embedding calls. */
public interface FashionSemanticIndexJobRepository {
    void enqueueUpsert(long wardrobeItemId);

    /** 入队一次删除：让该单品的向量索引失效（用于衣橱单品归档/删除）。 */
    void enqueueDelete(long wardrobeItemId);

    List<FashionSemanticIndexJob> claimPending(int limit, Duration lease, int maxAttempts);
    void complete(long jobId, String contentHash);
    void retry(long jobId, String failureSummary, int maxAttempts, Duration delay);
}
