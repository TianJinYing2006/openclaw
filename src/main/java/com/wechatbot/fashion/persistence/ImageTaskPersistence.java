package com.wechatbot.fashion.persistence;

import com.wechatbot.fashion.ai.service.ImageTaskStatusStore.ImageTask;
import java.util.List;
import java.util.Optional;

public interface ImageTaskPersistence {
    void save(String userId, ImageTask task);

    Optional<ImageTask> find(String userId, String taskId);

    List<ImageTask> recent(String userId, int limit);

    void clear(String userId);

    void markInterruptedTasksFailed();

    static ImageTaskPersistence disabled() { return Disabled.INSTANCE; }

    enum Disabled implements ImageTaskPersistence {
        INSTANCE;
        @Override public void save(String userId, ImageTask task) { }
        @Override public Optional<ImageTask> find(String userId, String taskId) { return Optional.empty(); }
        @Override public List<ImageTask> recent(String userId, int limit) { return List.of(); }
        @Override public void clear(String userId) { }
        @Override public void markInterruptedTasksFailed() { }
    }
}
