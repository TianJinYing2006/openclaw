package com.example.ykdsummer.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 本地磁盘工作区管理器。
 *
 * <p>按照 {@code {root}/{userId}/{sessionId}/{input|temp|output}/} 的目录结构管理
 * Agent 工作区文件。中间文件全部留在本地即时清理，只有最终交付文件才会被上传到 OSS。</p>
 *
 * <p><strong>路径传递：</strong>Tool 之间只传递文件路径字符串，不传递 {@code byte[]}，
 * 确保低内存占用。</p>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final FileStorageProperties properties;

    public FileStorageService(FileStorageProperties properties) {
        this.properties = properties;
    }

    // ========== 目录结构 ==========

    /** 为指定用户和会话创建工作区目录，返回 session 根目录。 */
    public Path createSession(String userId, String sessionId) {
        Path dir = sessionRoot(userId, sessionId);
        try {
            Files.createDirectories(dir.resolve("input"));
            Files.createDirectories(dir.resolve("temp"));
            Files.createDirectories(dir.resolve("output"));
            updateAccessTime(dir);
            log.debug("Created workspace session: {}", dir);
            return dir;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot create workspace session: " + dir, exception);
        }
    }

    /** 生成一个新的 sessionId 并创建工作区。 */
    public Session createSession(String userId) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Path root = createSession(userId, sessionId);
        return new Session(userId, sessionId, root);
    }

    /** input 子目录（微信下载的原始文件放这里）。 */
    public Path inputDir(String userId, String sessionId) {
        return ensureDir(sessionRoot(userId, sessionId).resolve("input"));
    }

    /** temp 子目录（中间处理文件放这里）。 */
    public Path tempDir(String userId, String sessionId) {
        return ensureDir(sessionRoot(userId, sessionId).resolve("temp"));
    }

    /** output 子目录（最终输出文件放这里）。 */
    public Path outputDir(String userId, String sessionId) {
        return ensureDir(sessionRoot(userId, sessionId).resolve("output"));
    }

    // ========== 文件操作 ==========

    /** 将字节写入指定路径，返回写入完成的路径。 */
    public Path writeFile(Path dir, String fileName, byte[] bytes) {
        try {
            Path target = dir.resolve(fileName).normalize();
            if (!target.startsWith(dir)) {
                throw new SecurityException("FileName escapes directory: " + fileName);
            }
            Files.write(target, bytes);
            updateAccessTime(dir.getParent()); // 更新 session 访问时间
            log.debug("Written {} bytes to {}", bytes.length, target);
            return target;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write file: " + fileName, exception);
        }
    }

    /** 将字节写入 input 目录。 */
    public Path writeInput(String userId, String sessionId, String fileName, byte[] bytes) {
        return writeFile(inputDir(userId, sessionId), fileName, bytes);
    }

    /** 将字节写入 temp 目录。 */
    public Path writeTemp(String userId, String sessionId, String fileName, byte[] bytes) {
        return writeFile(tempDir(userId, sessionId), fileName, bytes);
    }

    /** 将字节写入 output 目录。 */
    public Path writeOutput(String userId, String sessionId, String fileName, byte[] bytes) {
        return writeFile(outputDir(userId, sessionId), fileName, bytes);
    }

    /** 读取文件全部字节。 */
    public byte[] readFile(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read file: " + path, exception);
        }
    }

    // ========== 清理 ==========

    /** 立即删除整个 session 目录。由编排层在 finally 块中调用。 */
    public void deleteSession(String userId, String sessionId) {
        Path dir = sessionRoot(userId, sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(this::deleteQuietly);
            log.debug("Deleted workspace session: {}", dir);
        } catch (IOException exception) {
            log.warn("Failed to walk session directory for cleanup: {}", dir, exception);
        }
    }

    /** 刷新 session 的最后访问时间，供清理服务判断过期。 */
    public void touch(String userId, String sessionId) {
        Path dir = sessionRoot(userId, sessionId);
        if (Files.exists(dir)) {
            updateAccessTime(dir);
        }
    }

    // ========== 内部 ==========

    private Path sessionRoot(String userId, String sessionId) {
        return properties.getRoot()
                .resolve(sanitize(userId))
                .resolve(sanitize(sessionId));
    }

    /** 把用户 ID / sessionId 中的路径分隔符替换掉，防止目录穿越。 */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("userId/sessionId cannot be blank");
        }
        return value.replaceAll("[/\\\\:.]", "_");
    }

    private static Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (FileAlreadyExistsException ignore) {
            return dir;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot create directory: " + dir, exception);
        }
    }

    /** 通过修改目录的 lastModifiedTime 来标记访问时间。 */
    private static void updateAccessTime(Path dir) {
        try {
            Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // 非关键操作，忽略失败
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.trace("Cannot delete workspace file (will be cleaned later): {}", path, exception);
        }
    }

    /**
     * 一次会话的快照。记录 userId、sessionId 和根路径。
     */
    public record Session(String userId, String sessionId, Path root) {

        public Path inputDir() {
            return root.resolve("input");
        }

        public Path tempDir() {
            return root.resolve("temp");
        }

        public Path outputDir() {
            return root.resolve("output");
        }
    }
}
