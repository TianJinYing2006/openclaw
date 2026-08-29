package com.wechatbot.fashion.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 兜底清理服务。定时扫描工作区根目录，删除超过 {@code cleanupAge} 未访问的会话目录。
 *
 * <p>正常流程中，编排层会在 finally 块中即时清理会话目录。本服务只处理因进程崩溃、
 * 异常退出等导致的孤儿文件。</p>
 */
@Service
public class FileCleanupService {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupService.class);

    private final FileStorageProperties properties;

    public FileCleanupService(FileStorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void logSchedule() {
        Duration interval = properties.getCleanupInterval();
        Duration age = properties.getCleanupAge();
        log.info("Workspace cleanup scheduled: interval={}, maxAge={}, root={}",
                interval, age, properties.getRoot());
    }

    /**
     * 按配置的间隔执行兜底清理。扫描根目录下所有 userId 目录，删除其中过期的 session 目录。
     */
    @Scheduled(fixedDelayString = "${app.workspace.cleanup-interval:1m}")
    void cleanup() {
        Path root = properties.getRoot();
        if (!Files.isDirectory(root)) {
            return;
        }

        Instant deadline = Instant.now().minus(properties.getCleanupAge());
        int deleted = 0;

        try (Stream<Path> userDirs = Files.list(root)) {
            for (Path userDir : (Iterable<Path>) userDirs::iterator) {
                if (!Files.isDirectory(userDir)) continue;
                try (Stream<Path> sessionDirs = Files.list(userDir)) {
                    for (Path sessionDir : (Iterable<Path>) sessionDirs::iterator) {
                        if (!Files.isDirectory(sessionDir)) continue;
                        if (isExpired(sessionDir, deadline)) {
                            deleteQuietly(sessionDir);
                            deleted++;
                        }
                    }
                }
            }
        } catch (IOException exception) {
            log.warn("Workspace cleanup walk failed", exception);
        }

        if (deleted > 0) {
            log.info("Workspace cleanup removed {} expired session(s)", deleted);
        }
    }

    private boolean isExpired(Path sessionDir, Instant deadline) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(sessionDir);
            return lastModified.toInstant().isBefore(deadline);
        } catch (IOException exception) {
            // 无法读取时保守处理：不删除
            return false;
        }
    }

    private void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(this::deleteFileQuietly);
            log.debug("Cleaned up orphan workspace: {}", dir);
        } catch (IOException exception) {
            log.warn("Cannot clean up orphan workspace: {}", dir, exception);
        }
    }

    private void deleteFileQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.trace("Cannot delete workspace file: {}", path, exception);
        }
    }
}
