package com.example.ykdsummer.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地图片资产仓库。
 *
 * <p>目录按微信用户摘要隔离；一张全新图片会获得新的 {@code img_*} 资源 ID，同一张图的
 * 修改、回退会在该资源 ID 下追加 {@code v2、v3...}。元数据和“当前图片”指针同时写入磁盘，
 * 所以应用重启后仍能继续用 ID 找回图片。生产环境只需把这个类替换为 OSS + 数据库实现。</p>
 */
public class LocalImageAssetStore {
    private static final String CURRENT_FILE = "current-image.properties";

    private final Path root;
    private final ConcurrentMap<String, StoredImage> currentCache = new ConcurrentHashMap<>();

    public LocalImageAssetStore() {
        this(Path.of(".ai-assets", "images").toAbsolutePath().normalize());
    }

    public LocalImageAssetStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** 兼容旧调用：每次调用都表示一张全新的图片，而不是覆盖上一张。 */
    public StoredImage save(String userId, String prompt, byte[] bytes, String remoteUrl) {
        return saveGenerated(userId, prompt, bytes, remoteUrl);
    }

    public StoredImage saveGenerated(String userId, String prompt, byte[] bytes, String remoteUrl) {
        return saveNew(userId, "generated", prompt, bytes, remoteUrl, "image/png");
    }

    /** 保存用户从微信发来的图片，让后续“看上一张图/改那只猫”有可查的本地资源。 */
    public StoredImage saveIncoming(String userId, String prompt, byte[] bytes, String mediaType) {
        return saveNew(userId, "uploaded", prompt, bytes, null, mediaType);
    }

    /** 基于既有图片产生一个新版本；调用方必须先指定或查询对应 assetId。 */
    public StoredImage saveRevision(String userId, String assetId, String prompt, byte[] bytes, String remoteUrl) {
        ImageMetadata metadata = metadata(userId, assetId)
                .orElseThrow(() -> new IllegalArgumentException("找不到图片资源：" + assetId));
        return saveVersion(userId, metadata, prompt, bytes, remoteUrl, metadata.mediaType(), "generated-revision");
    }

    /** 将历史版本复制为新的当前版本，保留原版本不变。 */
    public StoredImage restore(String userId, String assetId, int targetVersion) {
        ImageMetadata metadata = metadata(userId, assetId)
                .orElseThrow(() -> new IllegalArgumentException("找不到图片资源：" + assetId));
        VersionMetadata version = metadata.version(targetVersion)
                .orElseThrow(() -> new IllegalArgumentException("找不到图片版本：" + assetId + " v" + targetVersion));
        byte[] bytes = readBytes(version.toStored(assetId, metadata.mediaType()));
        StoredImage restored = saveVersion(userId, metadata, "恢复自 v" + targetVersion + "：" + version.prompt(), bytes,
                null, metadata.mediaType(), "restored");
        return version.tags().isBlank()
                ? restored
                : annotate(userId, assetId, version.tags()).orElse(restored);
    }

    public Optional<StoredImage> current(String userId) {
        String key = safeUser(userId);
        StoredImage cached = currentCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path pointer = userDirectory(userId).resolve(CURRENT_FILE);
        if (!Files.isRegularFile(pointer)) {
            return Optional.empty();
        }
        Properties properties = read(pointer);
        String assetId = properties.getProperty("assetId", "");
        int version = integer(properties, "version", 0);
        return find(userId, assetId, version).map(found -> {
            currentCache.put(key, found);
            return found;
        });
    }

    /** 将指定图片版本设置为当前会话对象，不复制文件、不新增版本。 */
    public Optional<StoredImage> selectCurrent(String userId, String assetId, int version) {
        return find(userId, assetId, version).map(found -> {
            try {
                setCurrent(userId, found);
            } catch (IOException exception) {
                throw new IllegalStateException("无法设置当前图片资源", exception);
            }
            return found;
        });
    }

    /**
     * 清除“当前图片”的会话指针，不删除任何图片资源或历史版本。
     * 用户之后仍可在新的对话里通过已知的 assetId 找回对应图片。
     *
     * @return 本次是否实际移除了一个落盘指针
     */
    public boolean clearCurrent(String userId) {
        currentCache.remove(safeUser(userId));
        Path pointer = userDirectory(userId).resolve(CURRENT_FILE);
        try {
            return Files.deleteIfExists(pointer);
        } catch (IOException exception) {
            throw new IllegalStateException("无法清除当前图片会话指针", exception);
        }
    }

    public Optional<StoredImage> find(String userId, String assetId, int version) {
        if (!validAssetId(assetId) || version < 1) {
            return Optional.empty();
        }
        return metadata(userId, assetId).flatMap(metadata -> metadata.version(version)
                .map(value -> value.toStored(assetId, metadata.mediaType())));
    }

    /** 返回指定图片资源的最新版本，供工具处理非当前图片时定位。 */
    public Optional<StoredImage> latest(String userId, String assetId) {
        return metadata(userId, assetId).flatMap(ImageMetadata::latest);
    }

    /**
     * 保存由视觉模型得到的可检索摘要。摘要属于版本元数据，不会修改图片字节，也不会新增版本。
     * 后续模型先查看最近资源时，可以用“猫、人物、衣服颜色”等真实画面信息定位图片。
     */
    public Optional<StoredImage> annotate(String userId, String assetId, String visualSummary) {
        return metadata(userId, assetId).flatMap(metadata -> {
            int version = integer(metadata.properties(), "latestVersion", 0);
            Optional<VersionMetadata> target = metadata.version(version);
            if (target.isEmpty()) {
                return Optional.empty();
            }
            try {
                metadata.properties().setProperty("v" + version + ".tags", safe(visualSummary));
                write(metadata.assetDirectory().resolve("metadata.properties"), metadata.properties());
                StoredImage annotated = metadata.version(version)
                        .orElseThrow()
                        .toStored(metadata.assetId(), metadata.mediaType());
                current(userId).filter(value -> value.assetId().equals(assetId) && value.version() == version)
                        .ifPresent(ignored -> currentCache.put(safeUser(userId), annotated));
                return Optional.of(annotated);
            } catch (IOException exception) {
                throw new IllegalStateException("无法保存图片视觉摘要", exception);
            }
        });
    }

    public List<StoredImage> recent(String userId, int limit) {
        Path userDirectory = userDirectory(userId);
        if (!Files.isDirectory(userDirectory)) {
            return List.of();
        }
        try (var entries = Files.list(userDirectory)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> metadata(userId, path.getFileName().toString()))
                    .flatMap(Optional::stream)
                    .map(ImageMetadata::latest)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(StoredImage::createdAt).reversed())
                    .limit(Math.max(1, Math.min(limit, 20)))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    /** 本地开发/测试回退实现；生产环境会由 OssImageAssetStore 覆盖，不长期写入本机。 */
    public byte[] readBytes(StoredImage image) {
        try {
            return Files.readAllBytes(image.file());
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取图片资源", exception);
        }
    }

    /** 本地回退把原图内联成 data URL；图片编辑网关支持该格式，不需要访问本机文件路径。 */
    public String signedReadUrl(StoredImage image) {
        return "data:" + safeMediaType(image.mediaType()) + ";base64,"
                + Base64.getEncoder().encodeToString(readBytes(image));
    }

    private StoredImage saveNew(String userId, String source, String prompt, byte[] bytes, String remoteUrl, String mediaType) {
        String assetId = "img_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ImageMetadata metadata = new ImageMetadata(assetId, safeMediaType(mediaType), source,
                userDirectory(userId).resolve(assetId), new Properties());
        return saveVersion(userId, metadata, prompt, bytes, remoteUrl, metadata.mediaType(), source);
    }

    private StoredImage saveVersion(String userId, ImageMetadata metadata, String prompt, byte[] bytes,
                                    String remoteUrl, String mediaType, String source) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片内容不能为空");
        }
        int version = integer(metadata.properties(), "latestVersion", 0) + 1;
        String extension = extension(mediaType);
        Path assetDirectory = userDirectory(userId).resolve(metadata.assetId());
        Path file = assetDirectory.resolve("v" + version + "." + extension);
        try {
            Files.createDirectories(assetDirectory);
            Files.write(file, bytes.clone());
            Properties properties = metadata.properties();
            properties.setProperty("assetId", metadata.assetId());
            properties.setProperty("mediaType", safeMediaType(mediaType));
            properties.setProperty("source", source == null ? "unknown" : source);
            properties.setProperty("latestVersion", Integer.toString(version));
            properties.setProperty("v" + version + ".file", file.getFileName().toString());
            properties.setProperty("v" + version + ".prompt", safe(prompt));
            properties.setProperty("v" + version + ".tags", "");
            properties.setProperty("v" + version + ".source", source == null ? "unknown" : source);
            properties.setProperty("v" + version + ".remoteUrl", safe(remoteUrl));
            properties.setProperty("v" + version + ".createdAt", Instant.now().toString());
            write(assetDirectory.resolve("metadata.properties"), properties);
            StoredImage stored = new StoredImage(metadata.assetId(), version, file, safe(prompt), safe(remoteUrl),
                    Instant.parse(properties.getProperty("v" + version + ".createdAt")), safeMediaType(mediaType), source,
                    "");
            setCurrent(userId, stored);
            return stored;
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存本地图片资源", exception);
        }
    }

    private void setCurrent(String userId, StoredImage stored) throws IOException {
        Properties pointer = new Properties();
        pointer.setProperty("assetId", stored.assetId());
        pointer.setProperty("version", Integer.toString(stored.version()));
        write(userDirectory(userId).resolve(CURRENT_FILE), pointer);
        currentCache.put(safeUser(userId), stored);
    }

    private Optional<ImageMetadata> metadata(String userId, String assetId) {
        if (!validAssetId(assetId)) {
            return Optional.empty();
        }
        Path file = userDirectory(userId).resolve(assetId).resolve("metadata.properties");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = read(file);
        if (!assetId.equals(properties.getProperty("assetId"))) {
            return Optional.empty();
        }
        return Optional.of(new ImageMetadata(assetId, properties.getProperty("mediaType", "image/png"),
                properties.getProperty("source", "unknown"), file.getParent(), properties));
    }

    private Path userDirectory(String userId) {
        return root.resolve(safeUser(userId));
    }

    private static String safeUser(String userId) {
        return Integer.toHexString((userId == null ? "unknown" : userId).hashCode());
    }

    private static boolean validAssetId(String value) {
        return value != null && value.matches("img_[a-zA-Z0-9]{12}");
    }

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

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private static int integer(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Properties read(Path file) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取本地图片索引", exception);
        }
    }

    private static void write(Path file, Properties properties) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "Local iLink image asset index");
        }
    }

    private record ImageMetadata(String assetId, String mediaType, String source, Path assetDirectory,
                                 Properties properties) {
        private Optional<VersionMetadata> version(int number) {
            String name = properties.getProperty("v" + number + ".file");
            if (name == null || name.contains("/") || name.contains("\\")) {
                return Optional.empty();
            }
            Path file = assetDirectory.resolve(name).normalize();
            if (!file.getParent().equals(assetDirectory) || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(new VersionMetadata(number, file, properties.getProperty("v" + number + ".prompt", ""),
                    properties.getProperty("v" + number + ".remoteUrl", ""),
                    properties.getProperty("v" + number + ".createdAt", Instant.EPOCH.toString()),
                    properties.getProperty("v" + number + ".tags", ""),
                    properties.getProperty("v" + number + ".source", source)));
        }

        private Optional<StoredImage> latest() {
            return version(integer(properties, "latestVersion", 0))
                    .map(value -> value.toStored(assetId, mediaType));
        }
    }

    private record VersionMetadata(int version, Path file, String prompt, String remoteUrl, String createdAt,
                                   String tags, String source) {
        private StoredImage toStored(String assetId, String mediaType) {
            Instant timestamp;
            try {
                timestamp = Instant.parse(createdAt);
            } catch (Exception ignored) {
                timestamp = Instant.EPOCH;
            }
            return new StoredImage(assetId, version, file, prompt, remoteUrl, timestamp, mediaType, source, tags);
        }
    }

    public record StoredImage(String assetId, int version, Path file, String prompt, String remoteUrl,
                              Instant createdAt, String mediaType, String source, String tags) { }
}
