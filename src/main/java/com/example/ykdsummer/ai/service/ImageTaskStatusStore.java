package com.example.ykdsummer.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.ykdsummer.persistence.ImageTaskPersistence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 保存当前进程内最近的图片 Tool 执行状态。
 *
 * <p>图片服务仍在一次 Tool 调用内同步完成，这里记录的是 Java 已观察到的执行生命周期，
 * 不是伪造的云端异步任务队列。记录只保留重试所需的受限请求信息和安全状态摘要，不保存图片
 * 字节、远程 URL、原始用户 ID、密钥或供应商原始响应。</p>
 */
@Service
public class ImageTaskStatusStore {
    private static final Logger log = LoggerFactory.getLogger(ImageTaskStatusStore.class);
    private static final int MAX_TASKS_PER_USER = 12;
    private static final int MAX_RETRY_PROMPT_LENGTH = 4_000;
    private static final int MAX_FAILURE_LENGTH = 240;

    private final Cache<String, Deque<ImageTask>> tasksByUser = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();
    private volatile ImageTaskPersistence persistence = ImageTaskPersistence.disabled();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPersistence(ImageTaskPersistence persistence) {
        this.persistence = persistence == null ? ImageTaskPersistence.disabled() : persistence;
    }

    public ImageTask start(String userId, Operation operation, String retryPrompt,
                           String sourceAssetId, int sourceVersion) {
        ImageTask task = new ImageTask(
                "imgtask_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                operation == null ? Operation.GENERATE : operation,
                Status.RUNNING,
                Instant.now(),
                null,
                limit(retryPrompt, MAX_RETRY_PROMPT_LENGTH),
                safe(sourceAssetId),
                Math.max(0, sourceVersion),
                "",
                0,
                ""
        );
        Deque<ImageTask> tasks = tasksFor(userId);
        synchronized (tasks) {
            tasks.addFirst(task);
            while (tasks.size() > MAX_TASKS_PER_USER) {
                tasks.removeLast();
            }
        }
        persist(userId, task);
        return task;
    }

    public void succeed(String userId, String taskId, String resultAssetId, int resultVersion) {
        update(userId, taskId, task -> finished(task, Status.SUCCEEDED, resultAssetId, resultVersion, ""));
    }

    public void fail(String userId, String taskId, String failureSummary) {
        update(userId, taskId, task -> finished(task, Status.FAILED, "", 0, limit(failureSummary, MAX_FAILURE_LENGTH)));
    }

    public Optional<ImageTask> find(String userId, String taskId) {
        String requested = safe(taskId);
        if (requested.isBlank()) {
            return Optional.empty();
        }
        Optional<ImageTask> persisted = findPersisted(userId, requested);
        if (persisted.isPresent()) return persisted;
        return recentInMemory(userId, MAX_TASKS_PER_USER).stream()
                .filter(task -> task.taskId().equals(requested))
                .findFirst();
    }

    public Optional<ImageTask> latest(String userId) {
        return recent(userId, 1).stream().findFirst();
    }

    public Optional<ImageTask> latestFailed(String userId) {
        return recent(userId, MAX_TASKS_PER_USER).stream()
                .filter(task -> task.status() == Status.FAILED)
                .findFirst();
    }

    public List<ImageTask> running(String userId) {
        return recent(userId, MAX_TASKS_PER_USER).stream()
                .filter(task -> task.status() == Status.RUNNING)
                .toList();
    }

    public List<ImageTask> recent(String userId, int limit) {
        List<ImageTask> persisted = recentPersisted(userId, limit);
        if (!persisted.isEmpty()) return persisted;
        return recentInMemory(userId, limit);
    }

    private List<ImageTask> recentInMemory(String userId, int limit) {
        Deque<ImageTask> tasks = tasksByUser.getIfPresent(userKey(userId));
        if (tasks == null || limit < 1) {
            return List.of();
        }
        synchronized (tasks) {
            return tasks.stream().limit(Math.min(limit, MAX_TASKS_PER_USER)).toList();
        }
    }

    public void clear(String userId) {
        tasksByUser.invalidate(userKey(userId));
        try {
            persistence.clear(userId);
        } catch (RuntimeException exception) {
            log.warn("Could not clear persisted image tasks, user={}", anonymize(userId), exception);
        }
    }

    private void update(String userId, String taskId, UnaryOperator<ImageTask> updater) {
        ImageTask current = find(userId, taskId).orElse(null);
        if (current == null) return;
        ImageTask next = updater.apply(current);
        Deque<ImageTask> tasks = tasksFor(userId);
        synchronized (tasks) {
            List<ImageTask> updated = new ArrayList<>(tasks);
            for (int index = 0; index < updated.size(); index++) {
                if (updated.get(index).taskId().equals(taskId)) {
                    updated.set(index, next);
                    tasks.clear();
                    updated.forEach(tasks::addLast);
                    persist(userId, next);
                    return;
                }
            }
            tasks.addFirst(next);
            while (tasks.size() > MAX_TASKS_PER_USER) tasks.removeLast();
        }
        persist(userId, next);
    }

    private Deque<ImageTask> tasksFor(String userId) {
        return tasksByUser.get(userKey(userId), ignored -> new ArrayDeque<>());
    }

    private static ImageTask finished(ImageTask task, Status status, String resultAssetId,
                                      int resultVersion, String failureSummary) {
        Instant finishedAt = Instant.now();
        long durationMillis = Math.max(0, finishedAt.toEpochMilli() - task.startedAt().toEpochMilli());
        return new ImageTask(task.taskId(), task.operation(), status, task.startedAt(), finishedAt,
                task.retryPrompt(), task.sourceAssetId(), task.sourceVersion(), safe(resultAssetId),
                Math.max(0, resultVersion), safe(failureSummary));
    }

    private static String userKey(String value) {
        String user = safe(value);
        String normalized = user.isBlank() ? "unknown" : user;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private static String limit(String value, int maxLength) {
        String safe = safe(value);
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private void persist(String userId, ImageTask task) {
        try {
            persistence.save(userId, task);
        } catch (RuntimeException exception) {
            log.warn("Could not persist image task {}, user={}", task.taskId(), anonymize(userId), exception);
        }
    }

    private Optional<ImageTask> findPersisted(String userId, String taskId) {
        try {
            return persistence.find(userId, taskId);
        } catch (RuntimeException exception) {
            log.warn("Could not read persisted image task {}, user={}", taskId, anonymize(userId), exception);
            return Optional.empty();
        }
    }

    private List<ImageTask> recentPersisted(String userId, int limit) {
        try {
            return persistence.recent(userId, limit);
        } catch (RuntimeException exception) {
            log.warn("Could not read persisted image tasks, user={}", anonymize(userId), exception);
            return List.of();
        }
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    public enum Operation {
        GENERATE,
        REVISION
    }

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public record ImageTask(
            String taskId,
            Operation operation,
            Status status,
            Instant startedAt,
            Instant finishedAt,
            String retryPrompt,
            String sourceAssetId,
            int sourceVersion,
            String resultAssetId,
            int resultVersion,
            String failureSummary
    ) {
        public long elapsedMillis() {
            Instant end = finishedAt == null ? Instant.now() : finishedAt;
            return Math.max(0, end.toEpochMilli() - startedAt.toEpochMilli());
        }
    }
}
