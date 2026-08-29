package com.wechatbot.fashion.persistence;

import com.wechatbot.fashion.bot.file.LocalDocumentAssetStore.StoredDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcDocumentAssetMetadataStore implements DocumentAssetMetadataStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcDocumentAssetMetadataStore.class);
    private final JdbcTemplate jdbc;

    public JdbcDocumentAssetMetadataStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void record(String userId, StoredDocument document, String storageProvider) {
        if (userId == null || userId.isBlank() || document == null) return;
        try {
            ManagedInstanceScope scope = ManagedInstanceScope.parse(userId);
            Long platformUserId = scope.resolvePlatformUserId(jdbc);
            jdbc.update("""
                    INSERT INTO asset_versions(external_user_id, platform_user_id, instance_id, asset_id, version, asset_kind,
                        storage_provider, object_key, mime_type, source, prompt, tags, created_at)
                    VALUES (?, ?, ?, ?, ?, 'DOCUMENT', ?, ?, ?, 'document', ?, '', ?)
                    ON DUPLICATE KEY UPDATE platform_user_id = COALESCE(VALUES(platform_user_id), platform_user_id),
                        instance_id = COALESCE(VALUES(instance_id), instance_id), storage_provider = VALUES(storage_provider),
                        object_key = VALUES(object_key), mime_type = VALUES(mime_type), source = VALUES(source),
                        prompt = VALUES(prompt), updated_at = CURRENT_TIMESTAMP
                    """, userId, platformUserId, scope.instanceId(), document.assetId(), document.version(),
                    safe(storageProvider), document.file().toString(), mimeType(document.format()), safe(document.summary()),
                    java.sql.Timestamp.from(document.createdAt()));
        } catch (RuntimeException exception) {
            log.warn("Could not persist document asset metadata, user={}", Integer.toHexString(userId.hashCode()), exception);
        }
    }

    private static String mimeType(String format) {
        return switch (format == null ? "" : format.toLowerCase()) {
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

    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
