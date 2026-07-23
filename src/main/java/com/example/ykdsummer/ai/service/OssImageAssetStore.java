package com.example.ykdsummer.ai.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.example.ykdsummer.ai.config.OssImageProperties;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * 生产图片资产仓库。
 *
 * <p>图片字节、版本元数据和当前指针都保存到私有 OSS 对象；本机仅保留极短的当前指针缓存。
 * {@link StoredImage#file()} 在这里代表 OSS object key，不代表可直接读取的本地文件。所有读取必须
 * 通过 {@link #readBytes(StoredImage)}，改图则通过 {@link #signedReadUrl(StoredImage)} 取得短时 URL。</p>
 */
@Service
public class OssImageAssetStore extends LocalImageAssetStore {
    private static final String CURRENT_FILE = "current-image.properties";

    private final OssImageProperties properties;
    private final ConcurrentMap<String, StoredImage> currentCache = new ConcurrentHashMap<>();
    private volatile OSS client;

    @org.springframework.beans.factory.annotation.Autowired
    public OssImageAssetStore(OssImageProperties properties) {
        this.properties = properties;
    }

    /** 测试构造器：传入本地/模拟 OSS 客户端，不需要任何真实凭证。 */
    OssImageAssetStore(OssImageProperties properties, OSS client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public StoredImage save(String userId, String prompt, byte[] bytes, String remoteUrl) {
        return saveGenerated(userId, prompt, bytes, remoteUrl);
    }

    @Override
    public StoredImage saveGenerated(String userId, String prompt, byte[] bytes, String remoteUrl) {
        return saveNew(userId, "generated", prompt, bytes, remoteUrl, "image/png");
    }

    @Override
    public StoredImage saveIncoming(String userId, String prompt, byte[] bytes, String mediaType) {
        return saveNew(userId, "uploaded", prompt, bytes, null, mediaType);
    }

    @Override
    public StoredImage saveRevision(String userId, String assetId, String prompt, byte[] bytes, String remoteUrl) {
        ImageMetadata metadata = metadata(userId, assetId)
                .orElseThrow(() -> new IllegalArgumentException("找不到图片资源：" + assetId));
        return saveVersion(userId, metadata, prompt, bytes, remoteUrl, metadata.mediaType(), "generated-revision");
    }

    @Override
    public StoredImage restore(String userId, String assetId, int targetVersion) {
        ImageMetadata metadata = metadata(userId, assetId)
                .orElseThrow(() -> new IllegalArgumentException("找不到图片资源：" + assetId));
        VersionMetadata target = metadata.version(targetVersion)
                .orElseThrow(() -> new IllegalArgumentException("找不到图片版本：" + assetId + " v" + targetVersion));
        StoredImage original = target.toStored(assetId, metadata.mediaType());
        StoredImage restored = saveVersion(userId, metadata, "恢复自 v" + targetVersion + "：" + original.prompt(), readBytes(original),
                null, metadata.mediaType(), "restored");
        return target.tags().isBlank() ? restored : annotate(userId, assetId, target.tags()).orElse(restored);
    }

    @Override
    public Optional<StoredImage> current(String userId) {
        String userKey = safeUser(userId);
        StoredImage cached = currentCache.get(userKey);
        if (cached != null) return Optional.of(cached);
        Optional<Properties> pointer = readProperties(currentKey(userId));
        if (pointer.isEmpty()) return Optional.empty();
        String assetId = pointer.get().getProperty("assetId", "");
        int version = integer(pointer.get(), "version", 0);
        return find(userId, assetId, version).map(value -> {
            currentCache.put(userKey, value);
            return value;
        });
    }

    @Override
    public boolean clearCurrent(String userId) {
        currentCache.remove(safeUser(userId));
        String key = currentKey(userId);
        if (!exists(key)) return false;
        client().deleteObject(properties.getBucketName(), key);
        return true;
    }

    @Override
    public Optional<StoredImage> find(String userId, String assetId, int version) {
        if (!validAssetId(assetId) || version < 1) return Optional.empty();
        return metadata(userId, assetId).flatMap(metadata -> metadata.version(version)
                .map(value -> value.toStored(assetId, metadata.mediaType())));
    }

    @Override
    public Optional<StoredImage> latest(String userId, String assetId) {
        return metadata(userId, assetId).flatMap(ImageMetadata::latest);
    }

    @Override
    public Optional<StoredImage> annotate(String userId, String assetId, String visualSummary) {
        return metadata(userId, assetId).flatMap(metadata -> {
            int version = integer(metadata.properties(), "latestVersion", 0);
            Optional<VersionMetadata> target = metadata.version(version);
            if (target.isEmpty()) return Optional.empty();
            Properties changed = metadata.copy();
            changed.setProperty("v" + version + ".tags", safe(visualSummary));
            writeProperties(metadata.metadataKey(), changed);
            StoredImage result = new ImageMetadata(metadata.assetId(), metadata.mediaType(), metadata.source(),
                    metadata.metadataKey(), changed).version(version).orElseThrow()
                    .toStored(metadata.assetId(), metadata.mediaType());
            current(userId).filter(value -> value.assetId().equals(assetId) && value.version() == version)
                    .ifPresent(ignored -> currentCache.put(safeUser(userId), result));
            return Optional.of(result);
        });
    }

    @Override
    public List<StoredImage> recent(String userId, int limit) {
        ObjectListing listing = client().listObjects(properties.getBucketName(), userPrefix(userId));
        return listing.getObjectSummaries().stream()
                .map(OSSObjectSummary::getKey)
                .filter(key -> key.endsWith("/metadata.properties"))
                .map(this::readProperties)
                .flatMap(Optional::stream)
                .map(this::latestFromProperties)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(StoredImage::createdAt).reversed())
                .limit(Math.max(1, Math.min(limit, 20)))
                .toList();
    }

    @Override
    public byte[] readBytes(StoredImage image) {
        String key = image.file().toString().replace('\\', '/');
        try (OSSObject object = client().getObject(properties.getBucketName(), key);
             InputStream input = object.getObjectContent()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 OSS 图片资源", exception);
        }
    }

    /** 为异步图片网关签发只读、短时 URL；不把桶设为公开访问。 */
    @Override
    public String signedReadUrl(StoredImage image) {
        String key = image.file().toString().replace('\\', '/');
        Date expiration = Date.from(Instant.now().plus(properties.getSignedUrlTtl()));
        return client().generatePresignedUrl(properties.getBucketName(), key, expiration).toExternalForm();
    }

    private StoredImage saveNew(String userId, String source, String prompt, byte[] bytes, String remoteUrl, String mediaType) {
        String assetId = "img_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return saveVersion(userId, new ImageMetadata(assetId, safeMediaType(mediaType), source,
                        metadataKey(userId, assetId), new Properties()),
                prompt, bytes, remoteUrl, mediaType, source);
    }

    private StoredImage saveVersion(String userId, ImageMetadata metadata, String prompt, byte[] bytes,
                                    String remoteUrl, String mediaType, String source) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("图片内容不能为空");
        int version = integer(metadata.properties(), "latestVersion", 0) + 1;
        String key = imageKey(userId, metadata.assetId(), version, mediaType);
        putBytes(key, bytes, safeMediaType(mediaType));

        Properties changed = metadata.copy();
        changed.setProperty("assetId", metadata.assetId());
        changed.setProperty("mediaType", safeMediaType(mediaType));
        changed.setProperty("source", source == null ? "unknown" : source);
        changed.setProperty("latestVersion", Integer.toString(version));
        changed.setProperty("v" + version + ".key", key);
        changed.setProperty("v" + version + ".prompt", safe(prompt));
        changed.setProperty("v" + version + ".tags", "");
        changed.setProperty("v" + version + ".source", source == null ? "unknown" : source);
        changed.setProperty("v" + version + ".remoteUrl", safe(remoteUrl));
        changed.setProperty("v" + version + ".createdAt", Instant.now().toString());
        writeProperties(metadata.metadataKey(), changed);

        StoredImage saved = new StoredImage(metadata.assetId(), version, Path.of(key), safe(prompt), safe(remoteUrl),
                Instant.parse(changed.getProperty("v" + version + ".createdAt")), safeMediaType(mediaType), source, "");
        setCurrent(userId, saved);
        return saved;
    }

    private Optional<ImageMetadata> metadata(String userId, String assetId) {
        if (!validAssetId(assetId)) return Optional.empty();
        String metadataKey = metadataKey(userId, assetId);
        return readProperties(metadataKey).filter(props -> assetId.equals(props.getProperty("assetId")))
                .map(props -> new ImageMetadata(assetId, props.getProperty("mediaType", "image/png"),
                        props.getProperty("source", "unknown"), metadataKey, props));
    }

    private Optional<StoredImage> latestFromProperties(Properties properties) {
        String assetId = properties.getProperty("assetId", "");
        if (!validAssetId(assetId)) return Optional.empty();
        String mediaType = properties.getProperty("mediaType", "image/png");
        return new ImageMetadata(assetId, mediaType, properties.getProperty("source", "unknown"), "", properties).latest();
    }

    private void setCurrent(String userId, StoredImage image) {
        Properties pointer = new Properties();
        pointer.setProperty("assetId", image.assetId());
        pointer.setProperty("version", Integer.toString(image.version()));
        writeProperties(currentKey(userId), pointer);
        currentCache.put(safeUser(userId), image);
    }

    private Optional<Properties> readProperties(String key) {
        if (!exists(key)) return Optional.empty();
        try (OSSObject object = client().getObject(properties.getBucketName(), key);
             InputStream input = object.getObjectContent()) {
            Properties value = new Properties();
            value.load(input);
            return Optional.of(value);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 OSS 图片索引", exception);
        }
    }

    private void writeProperties(String key, Properties values) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            values.store(output, "iLink image asset index");
            putBytes(key, output.toByteArray(), "text/plain; charset=utf-8");
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入 OSS 图片索引", exception);
        }
    }

    private void putBytes(String key, byte[] bytes, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentType);
        client().putObject(properties.getBucketName(), key, new ByteArrayInputStream(bytes), metadata);
    }

    private boolean exists(String key) { return client().doesObjectExist(properties.getBucketName(), key); }

    private OSS client() {
        OSS current = client;
        if (current != null) return current;
        if (!properties.isConfigured()) {
            throw new IllegalStateException("图片 OSS 未配置，请设置 ALIOSS_ENDPOINT、ALIOSS_ACCESS_KEY_ID、ALIOSS_ACCESS_KEY_SECRET 和 ALIOSS_BUCKET_NAME");
        }
        synchronized (this) {
            if (client == null) {
                client = new OSSClientBuilder().build(properties.getEndpoint(), properties.getAccessKeyId(), properties.getAccessKeySecret());
            }
            return client;
        }
    }

    @PreDestroy
    void close() {
        OSS current = client;
        if (current != null) current.shutdown();
    }

    private String userPrefix(String userId) { return normalizedPrefix() + "/" + safeUser(userId) + "/"; }
    private String currentKey(String userId) { return userPrefix(userId) + CURRENT_FILE; }
    private String metadataKey(String userId, String assetId) { return userPrefix(userId) + assetId + "/metadata.properties"; }
    private String imageKey(String userId, String assetId, int version, String mediaType) {
        return userPrefix(userId) + assetId + "/v" + version + "." + extension(mediaType);
    }
    private String normalizedPrefix() {
        String raw = properties.getPrefix() == null ? "ilink-bot/images" : properties.getPrefix().strip();
        return raw.replaceAll("^/+|/+$", "");
    }
    private static String safeUser(String userId) { return Integer.toHexString((userId == null ? "unknown" : userId).hashCode()); }
    private static boolean validAssetId(String value) { return value != null && value.matches("img_[a-zA-Z0-9]{12}"); }
    private static String safeMediaType(String mediaType) {
        return switch (mediaType == null ? "" : mediaType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "image/jpeg";
            case "image/webp" -> "image/webp";
            default -> "image/png";
        };
    }
    private static String extension(String mediaType) {
        return switch (safeMediaType(mediaType)) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
    private static int integer(Properties values, String key, int fallback) {
        try { return Integer.parseInt(values.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private record ImageMetadata(String assetId, String mediaType, String source, String metadataKey, Properties properties) {
        private Properties copy() {
            Properties copy = new Properties();
            copy.putAll(properties);
            return copy;
        }
        private Optional<VersionMetadata> version(int number) {
            String key = properties.getProperty("v" + number + ".key");
            if (key == null || key.isBlank()) return Optional.empty();
            return Optional.of(new VersionMetadata(number, key, properties.getProperty("v" + number + ".prompt", ""),
                    properties.getProperty("v" + number + ".remoteUrl", ""),
                    properties.getProperty("v" + number + ".createdAt", Instant.EPOCH.toString()),
                    properties.getProperty("v" + number + ".tags", ""),
                    properties.getProperty("v" + number + ".source", source)));
        }
        private Optional<StoredImage> latest() { return version(integer(properties, "latestVersion", 0)).map(value -> value.toStored(assetId, mediaType)); }
    }

    private record VersionMetadata(int version, String key, String prompt, String remoteUrl, String createdAt, String tags, String source) {
        private StoredImage toStored(String assetId, String mediaType) {
            Instant timestamp;
            try { timestamp = Instant.parse(createdAt); } catch (Exception ignored) { timestamp = Instant.EPOCH; }
            return new StoredImage(assetId, version, Path.of(key), prompt, remoteUrl, timestamp, mediaType, source, tags);
        }
    }
}
