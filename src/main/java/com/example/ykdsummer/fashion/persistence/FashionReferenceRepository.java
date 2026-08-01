package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.FashionReferenceIndexJob;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for platform-owned outfit references and their indexing outbox. */
public interface FashionReferenceRepository {
    Optional<FashionReferenceLook> findByReferenceCode(String referenceCode);
    Optional<FashionReferenceLook> findBySha256(String sha256);
    Optional<FashionReferenceLook> findById(long id);
    List<FashionReferenceLook> findActiveByIds(List<Long> ids);
    FashionReferenceLook upsert(FashionReferenceLook draft);
    List<FashionReferenceLook> activeLooks(int limit);
    List<FashionReferenceIndexJob> claimPendingIndexJobs(int limit, Duration lease, int maxAttempts);
    void completeIndexJob(long jobId, String contentHash);
    void retryIndexJob(long jobId, String failureSummary, int maxAttempts, Duration delay);
}
