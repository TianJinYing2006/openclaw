package com.example.ykdsummer.bot.document;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.bot.config.DocumentEditProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按微信用户保存当前文档和不可覆盖的版本。文件落盘，活动状态只保存在当前 Java 进程。
 */
@Service
public class DocumentSessionService {

    private static final String METADATA_FILE = "metadata.json";

    private final DocumentEditProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ConcurrentHashMap<String, DocumentSession> sessions = new ConcurrentHashMap<>();

    public DocumentSessionService(DocumentEditProperties properties) {
        this.properties = properties;
    }

    public DocumentSnapshot open(String userId, AiFile file) {
        requireUser(userId);
        if (!properties.isEnabled()) {
            throw new DocumentEditException("Document mode disabled", "文档修改功能暂未启用");
        }
        close(userId);
        String extension = extension(file.fileName());
        String baseName = baseName(file.fileName());
        String documentId = UUID.randomUUID().toString();
        Path directory = safeRoot().resolve(userDirectory(userId)).resolve(documentId).normalize();
        ensureInsideRoot(directory);
        Path originalPath = directory.resolve("original." + extension);
        try {
            Files.createDirectories(directory);
            Files.write(originalPath, file.bytes());
        } catch (IOException exception) {
            throw new DocumentEditException("Could not persist original document", "文件保存失败，请重新发送", exception);
        }
        DocumentSession session = new DocumentSession(
                userId, documentId, file.fileName(), baseName, extension, file.mediaType(), directory,
                new ArrayList<>(List.of(new Version(1, 0, originalPath, "用户上传的原版", Instant.now()))),
                0, Instant.now()
        );
        sessions.put(userId, session);
        persistMetadata(session);
        return snapshot(session);
    }

    public boolean hasActive(String userId) {
        return activeSession(userId).isPresent();
    }

    public Optional<DocumentSnapshot> current(String userId) {
        return activeSession(userId).map(session -> {
            synchronized (session) {
                session.lastAccess = Instant.now();
                return snapshot(session);
            }
        });
    }

    public VersionFile currentFile(String userId) {
        DocumentSession session = requireSession(userId);
        synchronized (session) {
            session.lastAccess = Instant.now();
            Version version = session.versions.get(session.currentIndex);
            return readVersion(session, version);
        }
    }

    public DocumentSnapshot addVersion(String userId, byte[] bytes, String instruction) {
        DocumentSession session = requireSession(userId);
        synchronized (session) {
            if (session.versions.size() >= properties.getMaxVersions()) {
                throw new DocumentEditException("Document version limit reached", "当前文件版本已达到上限，请先完成或重新上传文件");
            }
            if (bytes == null || bytes.length == 0 || bytes.length > properties.getMaxOutputBytes()) {
                throw new DocumentEditException("Generated document size is invalid", "生成的文件大小无效，请简化要求后重试");
            }
            int versionNumber = session.versions.stream().mapToInt(Version::number).max().orElse(1) + 1;
            Path finalPath = session.directory.resolve(fileName(session.baseName, versionNumber, session.extension));
            Path temporaryPath = session.directory.resolve("." + finalPath.getFileName() + ".tmp");
            try {
                Files.write(temporaryPath, bytes);
                try {
                    Files.move(temporaryPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException unsupportedAtomicMove) {
                    Files.move(temporaryPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                throw new DocumentEditException("Could not persist document version", "新版本保存失败，请稍后重试", exception);
            }
            int parentVersion = session.versions.get(session.currentIndex).number;
            session.versions.add(new Version(
                    versionNumber, parentVersion, finalPath, safeInstruction(instruction), Instant.now()));
            session.currentIndex = session.versions.size() - 1;
            session.pendingInstruction = null;
            session.lastAccess = Instant.now();
            persistMetadata(session);
            return snapshot(session);
        }
    }

    public void savePendingInstruction(String userId, String instruction) {
        DocumentSession session = requireSession(userId);
        synchronized (session) {
            session.pendingInstruction = safeInstruction(instruction);
            session.lastAccess = Instant.now();
        }
    }

    public Optional<String> consumePendingInstruction(String userId) {
        DocumentSession session = requireSession(userId);
        synchronized (session) {
            String pending = session.pendingInstruction;
            session.pendingInstruction = null;
            session.lastAccess = Instant.now();
            return Optional.ofNullable(pending).filter(value -> !value.isBlank());
        }
    }

    public void clearPendingInstruction(String userId) {
        DocumentSession session = requireSession(userId);
        synchronized (session) {
            session.pendingInstruction = null;
            session.lastAccess = Instant.now();
        }
    }

    public void saveLastAnalysis(String userId, String analysis) {
        DocumentSession session = requireSession(userId);
        synchronized (session) {
            String value = analysis == null ? "" : analysis.strip();
            session.lastAnalysis = value.length() <= 8_000 ? value : value.substring(0, 8_000);
            session.pendingInstruction = null;
            session.lastAccess = Instant.now();
        }
    }

    public Optional<String> lastAnalysis(String userId) {
        DocumentSession session = requireSession(userId);
        synchronized (session) {
            session.lastAccess = Instant.now();
            return Optional.ofNullable(session.lastAnalysis).filter(value -> !value.isBlank());
        }
    }

    public Optional<DocumentSnapshot> undo(String userId) {
        DocumentSession session = requireSession(userId);
        synchronized (session) {
            Version current = session.versions.get(session.currentIndex);
            if (current.parentVersion == 0) {
                return Optional.empty();
            }
            session.currentIndex = indexOfVersion(session.versions, current.parentVersion);
            session.lastAccess = Instant.now();
            persistMetadata(session);
            return Optional.of(snapshot(session));
        }
    }

    public DocumentSnapshot useOriginal(String userId) {
        DocumentSession session = requireSession(userId);
        synchronized (session) {
            session.currentIndex = 0;
            session.lastAccess = Instant.now();
            persistMetadata(session);
            return snapshot(session);
        }
    }

    /** 退出文档模式但不删除已经写入磁盘的原版和历史版本。 */
    public Optional<DocumentSnapshot> close(String userId) {
        DocumentSession removed = sessions.remove(userId);
        return removed == null ? Optional.empty() : Optional.of(snapshot(removed));
    }

    @Scheduled(fixedDelay = 600_000L)
    void closeExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccess.plus(properties.getIdleTimeout()).isBefore(now));
    }

    private Optional<DocumentSession> activeSession(String userId) {
        DocumentSession session = sessions.get(userId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.lastAccess.plus(properties.getIdleTimeout()).isBefore(Instant.now())) {
            sessions.remove(userId, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    private DocumentSession requireSession(String userId) {
        return activeSession(userId).orElseThrow(() ->
                new DocumentEditException("No active document", "当前没有正在处理的文件，请先发送一个文件"));
    }

    private VersionFile readVersion(DocumentSession session, Version version) {
        try {
            return new VersionFile(
                    fileName(session.baseName, version.number, session.extension),
                    session.mediaType,
                    Files.readAllBytes(version.path),
                    version.number
            );
        } catch (IOException exception) {
            throw new DocumentEditException("Could not read current document", "当前文件读取失败，请重新上传", exception);
        }
    }

    private void persistMetadata(DocumentSession session) {
        try {
            List<VersionMetadata> versions = session.versions.stream()
                    .sorted(Comparator.comparingInt(Version::number))
                    .map(version -> new VersionMetadata(
                            version.number, version.parentVersion, version.path.getFileName().toString(),
                            version.instruction, version.createdAt))
                    .toList();
            Metadata metadata = new Metadata(
                    session.documentId, session.originalFileName,
                    session.mediaType, session.versions.get(session.currentIndex).number, versions);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(session.directory.resolve(METADATA_FILE).toFile(), metadata);
        } catch (IOException exception) {
            throw new DocumentEditException("Could not save document metadata", "文件版本信息保存失败，请重新上传", exception);
        }
    }

    private DocumentSnapshot snapshot(DocumentSession session) {
        synchronized (session) {
            Version current = session.versions.get(session.currentIndex);
            return new DocumentSnapshot(
                    session.documentId,
                    session.originalFileName,
                    session.extension,
                    current.number,
                    session.versions.size(),
                    session.lastAccess
            );
        }
    }

    private Path safeRoot() {
        return properties.getStorageDirectory().toAbsolutePath().normalize();
    }

    private void ensureInsideRoot(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(safeRoot())) {
            throw new DocumentEditException("Unsafe document path", "文件路径无效，请重命名后发送");
        }
    }

    private static String userDirectory(String userId) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            userId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    private static String baseName(String fileName) {
        String normalized = fileName.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1);
        int dot = normalized.lastIndexOf('.');
        String base = dot > 0 ? normalized.substring(0, dot) : normalized;
        String safe = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "document" : safe.substring(0, Math.min(80, safe.length()));
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new DocumentEditException("Document has no extension", "文件缺少扩展名，请重命名后发送");
        }
        return fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String fileName(String baseName, int version, String extension) {
        return baseName + (version == 1 ? "_original" : "_v" + version) + "." + extension;
    }

    private static String safeInstruction(String instruction) {
        String text = instruction == null ? "" : instruction.strip();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static int indexOfVersion(List<Version> versions, int versionNumber) {
        for (int index = 0; index < versions.size(); index++) {
            if (versions.get(index).number == versionNumber) {
                return index;
            }
        }
        throw new DocumentEditException("Document parent version is missing", "文件版本关系损坏，请重新上传文件");
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be blank");
        }
    }

    public record DocumentSnapshot(
            String documentId,
            String originalFileName,
            String extension,
            int currentVersion,
            int versionCount,
            Instant lastAccess
    ) {
    }

    public record VersionFile(String fileName, String mediaType, byte[] bytes, int version) {
        public VersionFile { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        public AiFile asAiFile() { return new AiFile(fileName, mediaType, bytes); }
    }

    private static final class DocumentSession {
        private final String userId;
        private final String documentId;
        private final String originalFileName;
        private final String baseName;
        private final String extension;
        private final String mediaType;
        private final Path directory;
        private final List<Version> versions;
        private int currentIndex;
        private Instant lastAccess;
        private String pendingInstruction;
        private String lastAnalysis;

        private DocumentSession(String userId, String documentId, String originalFileName,
                                String baseName, String extension,
                                String mediaType, Path directory, List<Version> versions,
                                int currentIndex, Instant lastAccess) {
            this.userId = userId;
            this.documentId = documentId;
            this.originalFileName = originalFileName;
            this.baseName = baseName;
            this.extension = extension;
            this.mediaType = mediaType;
            this.directory = directory;
            this.versions = versions;
            this.currentIndex = currentIndex;
            this.lastAccess = lastAccess;
        }
    }

    private record Version(int number, int parentVersion, Path path, String instruction, Instant createdAt) { }
    private record VersionMetadata(int number, int parentVersion, String fileName,
                                   String instruction, Instant createdAt) { }
    private record Metadata(String documentId, String originalFileName, String mediaType,
                            int currentVersion, List<VersionMetadata> versions) { }
}
