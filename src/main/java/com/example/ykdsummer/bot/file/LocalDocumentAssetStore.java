package com.example.ykdsummer.bot.file;

import com.example.ykdsummer.ai.model.AiFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * 文档的本地版本仓库。
 *
 * <p>用户上传的文件、模型新建的文件和后续修改都写成不可覆盖的版本文件。回退不是删除新版本，
 * 而是将目标历史版本复制成一个新的当前版本，因此资源 ID、版本号和文件内容始终可追踪。</p>
 */
@Service
public class LocalDocumentAssetStore {
    private static final String CURRENT_FILE = "current-document.properties";

    private final Path root;
    private final ConcurrentMap<String, StoredDocument> currentCache = new ConcurrentHashMap<>();

    public LocalDocumentAssetStore() {
        this(Path.of(".ai-assets", "documents").toAbsolutePath().normalize());
    }

    LocalDocumentAssetStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public StoredDocument importUploaded(String userId, AiFile source) {
        if (source == null) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String format = extension(source.fileName());
        requireFormat(format);
        return create(userId, source.fileName(), format, source.bytes(), "微信上传文件");
    }

    public StoredDocument create(String userId, String title, String format, byte[] bytes, String summary) {
        String normalized = normalizeFormat(format);
        String assetId = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return saveVersion(userId, assetId, title, normalized, bytes, summary, 1, "created");
    }

    public StoredDocument revise(String userId, String assetId, String title, String format, byte[] bytes, String summary) {
        StoredDocument current = requireCurrentAsset(userId, assetId);
        String normalized = format == null || format.isBlank() ? current.format() : normalizeFormat(format);
        return saveVersion(userId, assetId, title == null || title.isBlank() ? current.fileName() : title,
                normalized, bytes, summary, current.latestVersion() + 1, "revised");
    }

    public StoredDocument restore(String userId, String assetId, int targetVersion) {
        StoredDocument current = requireCurrentAsset(userId, assetId);
        StoredDocument target = find(userId, assetId, targetVersion)
                .orElseThrow(() -> new IllegalArgumentException("找不到文档版本：" + assetId + " v" + targetVersion));
        try {
            return saveVersion(userId, assetId, current.fileName(), target.format(), Files.readAllBytes(target.file()),
                    "恢复自 v" + targetVersion + "：" + target.summary(), current.latestVersion() + 1, "restored");
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取要恢复的文档版本", exception);
        }
    }

    public Optional<StoredDocument> current(String userId) {
        String key = safeUser(userId);
        StoredDocument cached = currentCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path pointer = userDirectory(userId).resolve(CURRENT_FILE);
        if (!Files.isRegularFile(pointer)) {
            return Optional.empty();
        }
        Properties properties = read(pointer);
        return find(userId, properties.getProperty("assetId", ""), integer(properties, "version", 0))
                .map(value -> {
                    currentCache.put(key, value);
                    return value;
                });
    }

    /**
     * 清除“当前文档”的会话指针，不删除文档资源目录或任何历史版本。
     * 这使“清除对话记忆”不会误删用户已经生成或上传的文件。
     *
     * @return 本次是否实际移除了一个落盘指针
     */
    public boolean clearCurrent(String userId) {
        currentCache.remove(safeUser(userId));
        Path pointer = userDirectory(userId).resolve(CURRENT_FILE);
        try {
            return Files.deleteIfExists(pointer);
        } catch (IOException exception) {
            throw new IllegalStateException("无法清除当前文档会话指针", exception);
        }
    }

    public Optional<StoredDocument> find(String userId, String assetId, int version) {
        if (!validAssetId(assetId) || version < 1) {
            return Optional.empty();
        }
        Path assetDirectory = userDirectory(userId).resolve(assetId);
        Properties properties = metadata(assetDirectory, assetId).orElse(null);
        if (properties == null) {
            return Optional.empty();
        }
        String storedName = properties.getProperty("v" + version + ".file");
        if (storedName == null || storedName.contains("/") || storedName.contains("\\")) {
            return Optional.empty();
        }
        Path file = assetDirectory.resolve(storedName).normalize();
        if (!file.getParent().equals(assetDirectory) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(new StoredDocument(
                assetId,
                version,
                integer(properties, "latestVersion", version),
                file,
                properties.getProperty("v" + version + ".fileName", storedName),
                properties.getProperty("v" + version + ".format", extension(storedName)),
                properties.getProperty("v" + version + ".summary", ""),
                timestamp(properties.getProperty("v" + version + ".createdAt"))
        ));
    }

    public List<StoredDocument> versions(String userId, String assetId) {
        Path assetDirectory = userDirectory(userId).resolve(assetId == null ? "" : assetId);
        Properties properties = metadata(assetDirectory, assetId).orElse(null);
        if (properties == null) {
            return List.of();
        }
        int latest = integer(properties, "latestVersion", 0);
        return java.util.stream.IntStream.rangeClosed(1, latest)
                .mapToObj(version -> find(userId, assetId, version))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(StoredDocument::version))
                .toList();
    }

    public byte[] readBytes(StoredDocument document) {
        try {
            return Files.readAllBytes(document.file());
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取本地文档资源", exception);
        }
    }

    private StoredDocument saveVersion(String userId, String assetId, String title, String format, byte[] bytes,
                                       String summary, int version, String action) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("文档版本无效");
        }
        String normalized = normalizeFormat(format);
        Path directory = userDirectory(userId).resolve(assetId);
        String fileName = safeFileName(title, normalized);
        Path file = directory.resolve("v" + version + "-" + fileName);
        try {
            Files.createDirectories(directory);
            Files.write(file, bytes.clone());
            Properties properties = metadata(directory, assetId).orElseGet(Properties::new);
            properties.setProperty("assetId", assetId);
            properties.setProperty("latestVersion", Integer.toString(version));
            properties.setProperty("v" + version + ".file", file.getFileName().toString());
            properties.setProperty("v" + version + ".fileName", fileName);
            properties.setProperty("v" + version + ".format", normalized);
            properties.setProperty("v" + version + ".summary", safe(summary));
            properties.setProperty("v" + version + ".action", action);
            properties.setProperty("v" + version + ".createdAt", Instant.now().toString());
            write(directory.resolve("metadata.properties"), properties);
            StoredDocument stored = find(userId, assetId, version).orElseThrow();
            setCurrent(userId, stored);
            return stored;
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存本地文档资源", exception);
        }
    }

    private void setCurrent(String userId, StoredDocument stored) throws IOException {
        Properties pointer = new Properties();
        pointer.setProperty("assetId", stored.assetId());
        pointer.setProperty("version", Integer.toString(stored.version()));
        write(userDirectory(userId).resolve(CURRENT_FILE), pointer);
        currentCache.put(safeUser(userId), stored);
    }

    private StoredDocument requireCurrentAsset(String userId, String assetId) {
        if (!validAssetId(assetId)) {
            throw new IllegalArgumentException("文档资源 ID 无效");
        }
        List<StoredDocument> versions = versions(userId, assetId);
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("找不到文档资源：" + assetId);
        }
        return versions.getLast();
    }

    private static Optional<Properties> metadata(Path assetDirectory, String assetId) {
        if (!validAssetId(assetId)) {
            return Optional.empty();
        }
        Path file = assetDirectory.resolve("metadata.properties");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = read(file);
        return assetId.equals(properties.getProperty("assetId")) ? Optional.of(properties) : Optional.empty();
    }

    private Path userDirectory(String userId) {
        return root.resolve(safeUser(userId));
    }

    private static String normalizeFormat(String format) {
        String value = format == null ? "" : format.strip().toLowerCase();
        return switch (value) {
            case "word" -> "docx";
            case "excel" -> "xlsx";
            case "text" -> "txt";
            case "docx", "xlsx", "pdf", "txt" -> value;
            default -> throw new IllegalArgumentException("暂时只支持 Word、Excel、PDF 和 TXT 文件");
        };
    }

    private static void requireFormat(String format) {
        normalizeFormat(format);
    }

    private static String extension(String value) {
        int dot = value == null ? -1 : value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1).toLowerCase();
    }

    private static String safeFileName(String title, String format) {
        String base = title == null ? "document" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (base.isBlank()) {
            base = "document";
        }
        // `revise` 通常传入上一版的 fileName。跨格式转换时先去掉已知的旧扩展名，
        // 否则 Word 转 PDF 会变成 `报告.docx.pdf`，让微信端和用户都难以判断真实类型。
        int dot = base.lastIndexOf('.');
        if (dot > 0 && dot < base.length() - 1 && isDocumentFormat(base.substring(dot + 1))) {
            base = base.substring(0, dot);
        }
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return base + "." + format;
    }

    private static boolean isDocumentFormat(String value) {
        return switch (value == null ? "" : value.strip().toLowerCase()) {
            case "docx", "xlsx", "pdf", "txt" -> true;
            default -> false;
        };
    }

    private static String safeUser(String userId) {
        return Integer.toHexString((userId == null ? "unknown" : userId).hashCode());
    }

    private static boolean validAssetId(String value) {
        return value != null && value.matches("doc_[a-zA-Z0-9]{12}");
    }

    private static String safe(String text) {
        return text == null ? "" : text.replace('\u0000', ' ').strip();
    }

    private static int integer(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Instant timestamp(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }

    private static Properties read(Path file) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取本地文档索引", exception);
        }
    }

    private static void write(Path file, Properties properties) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "Local iLink document asset index");
        }
    }

    public record StoredDocument(String assetId, int version, int latestVersion, Path file, String fileName,
                                 String format, String summary, Instant createdAt) { }
}
