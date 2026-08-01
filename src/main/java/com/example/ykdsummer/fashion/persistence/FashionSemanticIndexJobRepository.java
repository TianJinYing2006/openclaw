package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.FashionSemanticIndexJob;
import java.time.Duration;
import java.util.List;

/** Durable queue used to decouple wardrobe writes from external embedding calls. */
public interface FashionSemanticIndexJobRepository {
    void enqueueUpsert(long wardrobeItemId);
    List<FashionSemanticIndexJob> claimPending(int limit, Duration lease, int maxAttempts);
    void complete(long jobId, String contentHash);
    void retry(long jobId, String failureSummary, int maxAttempts, Duration delay);
}
