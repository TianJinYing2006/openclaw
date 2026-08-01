package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.FashionTryOnTask;
import com.example.ykdsummer.fashion.domain.FashionTryOnWork;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for durable, user-scoped virtual try-on work. */
public interface FashionTryOnRepository {
    FashionTryOnTask submit(String externalUserId, long wardrobeItemId);
    List<String> pendingTaskIds(int limit);
    Optional<FashionTryOnWork> claim(String taskId, Instant now);
    void succeed(String taskId, String outputAssetId, int outputVersion, Instant completedAt);
    void fail(String taskId, String failureSummary, Instant completedAt);
    Optional<FashionTryOnTask> latest(String externalUserId);
    int recoverInterruptedTasks();
}
