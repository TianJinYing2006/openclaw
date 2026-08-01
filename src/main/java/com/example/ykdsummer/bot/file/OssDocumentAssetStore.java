package com.example.ykdsummer.bot.file;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.example.ykdsummer.ai.config.OssDocumentProperties;
import com.example.ykdsummer.ai.config.OssImageProperties;
import com.example.ykdsummer.ai.model.AiFile;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Private OSS-backed, versioned document repository. Local disk remains only a migration source and work area. */
@Service
@ConditionalOnProperty(prefix = "oss.document", name = "enabled", havingValue = "true")
public class OssDocumentAssetStore extends LocalDocumentAssetStore {
    private static final String CURRENT_FILE = "current-document.properties";

    private final OssImageProperties connection;
    private final OssDocumentProperties properties;
    private final ConcurrentMap<String, StoredDocument> currentCache = new ConcurrentHashMap<>();
    private volatile OSS client;

    @org.springframework.beans.factory.annotation.Autowired
    public OssDocumentAssetStore(OssImageProperties connection, OssDocumentProperties properties) {
        this.connection = connection;
        this.properties = properties;
    }

    OssDocumentAssetStore(OssImageProperties connection, OssDocumentProperties properties, OSS client) {
        this.connection = connection;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public StoredDocument importUploaded(String userId, AiFile source) {
        if (source == null) throw new IllegalArgumentException("上传文件不能为空");
        String format = extension(source.fileName());
        return create(userId, source.fileName(), format, source.bytes(), "微信上传文件");
    }

    @Override
    public StoredDocument create(String userId, String title, String format, byte[] bytes, String summary) {
        String normalized = normalizeFormat(format);
        String assetId = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return saveVersion(userId, assetId, title, normalized, bytes, summary, 1, "created", Instant.now(), true);
    }

    @Override
    public StoredDocument revise(String userId, String assetId, String title, String format, byte[] bytes, String summary) {
        StoredDocument current = requireLatest(userId, assetId);
        String normalized = format == null || format.isBlank() ? current.format() : normalizeFormat(format);
        return saveVersion(userId, assetId, title == null || title.isBlank() ? current.fileName() : title, normalized, bytes,
                summary, current.latestVersion() + 1, "revised", Instant.now(), true);
    }

    @Override
    public StoredDocument restore(String userId, String assetId, int targetVersion) {
        StoredDocument current = requireLatest(userId, assetId);
        StoredDocument target = find(userId, assetId, targetVersion)
                .orElseThrow(() -> new IllegalArgumentException("找不到文档版本：" + assetId + " v" + targetVersion));
        return saveVersion(userId, assetId, current.fileName(), target.format(), readBytes(target),
                "恢复自 v" + targetVersion + "：" + target.summary(), current.latestVersion() + 1,
                "restored", Instant.now(), true);
    }

    /** Idempotent local-to-OSS import that preserves the existing asset ID, version, and timestamp. */
    public StoredDocument importLegacy(String userId, StoredDocument legacy, byte[] bytes) {
        if (legacy == null || !validAssetId(legacy.assetId())) throw new IllegalArgumentException("文档资源 ID 无效");
        Optional<DocumentMetadata> existing = metadata(userId, legacy.assetId());
        if (existing.flatMap(value -> value.version(legacy.version())).isPresent()) {
            return existing.get().version(legacy.version()).orElseThrow();
        }
        int latest = existing.map(value -> integer(value.properties(), "latestVersion", 0)).orElse(0);
        if (legacy.version() != latest + 1) {
            throw new IllegalStateException("本地文档版本不连续，无法安全迁移：" + legacy.assetId());
        }
        return saveVersion(userId, legacy.assetId(), legacy.fileName(), legacy.format(), bytes, legacy.summary(),
                legacy.version(), "migrated-local", legacy.createdAt(), false);
    }

    @Override
    public Optional<StoredDocument> current(String userId) {
        String key = safeUser(userId);
        StoredDocument cached = currentCache.get(key);
        if (cached != null) return Optional.of(cached);
        return readProperties(currentKey(userId)).flatMap(pointer -> find(userId, pointer.getProperty("assetId", ""),
                integer(pointer, "version", 0))).map(found -> {
            currentCache.put(key, found);
            return found;
        });
    }

    @Override
    public Optional<StoredDocument> selectCurrent(String userId, String assetId, int version) {
        StoredDocument cached = currentCache.get(safeUser(userId));
        if (cached != null && cached.assetId().equals(assetId) && cached.version() == version) {
            setCurrent(userId, cached);
            return Optional.of(cached);
        }
        return find(userId, assetId, version).map(found -> {
            setCurrent(userId, found);
            return found;
        });
    }

    @Override
    public boolean clearCurrent(String userId) {
        currentCache.remove(safeUser(userId));
        String key = currentKey(userId);
        if (!exists(key)) return false;
        client().deleteObject(connection.getBucketName(), key);
        return true;
    }

    @Override
    public Optional<StoredDocument> find(String userId, String assetId, int version) {
        if (!validAssetId(assetId) || version < 1) return Optional.empty();
        return metadata(userId, assetId).flatMap(value -> value.version(version));
    }

    @Override
    public List<StoredDocument> versions(String userId, String assetId) {
        return metadata(userId, assetId).map(value -> {
            int latest = integer(value.properties(), "latestVersion", 0);
            return java.util.stream.IntStream.rangeClosed(1, latest).mapToObj(value::version)
                    .flatMap(Optional::stream).toList();
        }).orElseGet(List::of);
    }

    @Override
    public List<StoredDocument> recent(String userId, int limit) {
        ObjectListing listing = client().listObjects(connection.getBucketName(), userPrefix(userId));
        return listing.getObjectSummaries().stream().map(OSSObjectSummary::getKey)
                .filter(key -> key.endsWith("/metadata.properties")).map(this::readProperties)
                .flatMap(Optional::stream).map(this::latestFromProperties).flatMap(Optional::stream)
                .sorted(Comparator.comparing(StoredDocument::createdAt).reversed())
                .limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    @Override
    public byte[] readBytes(StoredDocument document) {
        String key = document.file().toString().replace('\\', '/');
        try (OSSObject object = client().getObject(connection.getBucketName(), key);
             InputStream input = object.getObjectContent()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 OSS 文档资源", exception);
        }
    }

    @Override
    protected String storageProvider() { return "oss"; }

    private StoredDocument saveVersion(String userId, String assetId, String title, String format, byte[] bytes, String summary,
                                       int version, String action, Instant createdAt, boolean makeCurrent) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("文档内容不能为空");
        String normalized = normalizeFormat(format);
        String fileName = safeFileName(title, normalized);
        DocumentMetadata existing = metadata(userId, assetId).orElse(new DocumentMetadata(assetId, metadataKey(userId, assetId), new Properties()));
        String objectKey = documentKey(userId, assetId, version, fileName);
        putBytes(objectKey, bytes, mimeType(normalized));

        Properties changed = existing.copy();
        changed.setProperty("assetId", assetId);
        changed.setProperty("latestVersion", Integer.toString(Math.max(version, integer(changed, "latestVersion", 0))));
        changed.setProperty("v" + version + ".key", objectKey);
        changed.setProperty("v" + version + ".fileName", fileName);
        changed.setProperty("v" + version + ".format", normalized);
        changed.setProperty("v" + version + ".summary", safe(summary));
        changed.setProperty("v" + version + ".action", safe(action));
        changed.setProperty("v" + version + ".createdAt", createdAt.toString());
        writeProperties(existing.metadataKey(), changed);

        StoredDocument stored = new DocumentMetadata(assetId, existing.metadataKey(), changed).version(version).orElseThrow();
        if (makeCurrent) setCurrent(userId, stored);
        recordAsset(userId, stored);
        return stored;
    }

    private StoredDocument requireLatest(String userId, String assetId) {
        if (!validAssetId(assetId)) throw new IllegalArgumentException("文档资源 ID 无效");
        return metadata(userId, assetId).flatMap(DocumentMetadata::latest)
                .orElseThrow(() -> new IllegalArgumentException("找不到文档资源：" + assetId));
    }

    private Optional<DocumentMetadata> metadata(String userId, String assetId) {
        if (!validAssetId(assetId)) return Optional.empty();
        String key = metadataKey(userId, assetId);
        return readProperties(key).filter(values -> assetId.equals(values.getProperty("assetId")))
                .map(values -> new DocumentMetadata(assetId, key, values));
    }

    private Optional<StoredDocument> latestFromProperties(Properties values) {
        String assetId = values.getProperty("assetId", "");
        if (!validAssetId(assetId)) return Optional.empty();
        return new DocumentMetadata(assetId, "", values).latest();
    }

    private void setCurrent(String userId, StoredDocument document) {
        Properties pointer = new Properties();
        pointer.setProperty("assetId", document.assetId());
        pointer.setProperty("version", Integer.toString(document.version()));
        writeProperties(currentKey(userId), pointer);
        currentCache.put(safeUser(userId), document);
    }

    private Optional<Properties> readProperties(String key) {
        if (!exists(key)) return Optional.empty();
        try (OSSObject object = client().getObject(connection.getBucketName(), key);
             InputStream input = object.getObjectContent()) {
            Properties values = new Properties();
            values.load(input);
            return Optional.of(values);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 OSS 文档索引", exception);
        }
    }

    private void writeProperties(String key, Properties values) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            values.store(output, "iLink document asset index");
            putBytes(key, output.toByteArray(), "text/plain; charset=utf-8");
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入 OSS 文档索引", exception);
        }
    }

    private void putBytes(String key, byte[] bytes, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentType);
        client().putObject(connection.getBucketName(), key, new ByteArrayInputStream(bytes), metadata);
    }

    private boolean exists(String key) { return client().doesObjectExist(connection.getBucketName(), key); }

    private OSS client() {
        OSS current = client;
        if (current != null) return current;
        if (!connection.isConfigured()) {
            throw new IllegalStateException("文档 OSS 未配置，请先配置图片 OSS 的连接参数");
        }
        synchronized (this) {
            if (client == null) {
                client = new OSSClientBuilder().build(connection.getEndpoint(), connection.getAccessKeyId(), connection.getAccessKeySecret());
            }
            return client;
        }
    }

    private String userPrefix(String userId) { return normalizedPrefix() + "/" + safeUser(userId) + "/"; }
    private String currentKey(String userId) { return userPrefix(userId) + CURRENT_FILE; }
    private String metadataKey(String userId, String assetId) { return userPrefix(userId) + assetId + "/metadata.properties"; }
    private String documentKey(String userId, String assetId, int version, String fileName) {
        return userPrefix(userId) + assetId + "/v" + version + "-" + fileName;
    }
    private String normalizedPrefix() { return properties.getPrefix().replaceAll("^/+|/+$", ""); }

    @PreDestroy
    void close() {
        OSS current = client;
        if (current != null) current.shutdown();
    }

    private static boolean validAssetId(String value) { return value != null && value.matches("doc_[a-zA-Z0-9]{12}"); }
    private static String safeUser(String userId) { return Integer.toHexString((userId == null ? "unknown" : userId).hashCode()); }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
    private static int integer(Properties values, String key, int fallback) {
        try { return Integer.parseInt(values.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static String extension(String value) {
        int dot = value == null ? -1 : value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1).toLowerCase();
    }
    private static String safeFileName(String title, String format) {
        String base = title == null ? "document" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (base.isBlank()) base = "document";
        int dot = base.lastIndexOf('.');
        if (dot > 0 && dot < base.length() - 1 && isDocumentFormat(base.substring(dot + 1))) {
            base = base.substring(0, dot);
        }
        return (base.length() > 80 ? base.substring(0, 80) : base) + "." + format;
    }
    private static boolean isDocumentFormat(String value) {
        return switch (value == null ? "" : value.strip().toLowerCase()) {
            case "docx", "xlsx", "pptx", "pdf", "txt", "md", "html", "htm", "csv", "json", "xml" -> true;
            default -> false;
        };
    }
    private static String mimeType(String format) {
        return switch (format) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "pdf" -> "application/pdf";
            case "csv" -> "text/csv";
            case "html" -> "text/html; charset=utf-8";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "md" -> "text/markdown; charset=utf-8";
            default -> "text/plain; charset=utf-8";
        };
    }

    private record DocumentMetadata(String assetId, String metadataKey, Properties properties) {
        private Properties copy() {
            Properties copy = new Properties();
            copy.putAll(properties);
            return copy;
        }
        private Optional<StoredDocument> latest() {
            return version(integer(properties, "latestVersion", 0));
        }
        private Optional<StoredDocument> version(int number) {
            String key = properties.getProperty("v" + number + ".key", "");
            if (key.isBlank()) return Optional.empty();
            String fileName = properties.getProperty("v" + number + ".fileName", "document.bin");
            String format = properties.getProperty("v" + number + ".format", extension(fileName));
            return Optional.of(new StoredDocument(assetId, number, integer(properties, "latestVersion", number), Path.of(key),
                    fileName, format, properties.getProperty("v" + number + ".summary", ""),
                    timestamp(properties.getProperty("v" + number + ".createdAt"))));
        }
    }

    private static Instant timestamp(String value) {
        try { return Instant.parse(value); }
        catch (Exception ignored) { return Instant.EPOCH; }
    }
}
