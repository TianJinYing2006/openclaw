package com.example.ykdsummer.persistence;

import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcImageAssetMetadataStore implements ImageAssetMetadataStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcImageAssetMetadataStore.class);
    private final JdbcTemplate jdbc;

    public JdbcImageAssetMetadataStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void record(String userId, StoredImage image, String storageProvider) {
        if (userId == null || userId.isBlank() || image == null) return;
        try {
            jdbc.update("""
                    INSERT INTO asset_versions(external_user_id, asset_id, version, asset_kind, storage_provider,
                        object_key, mime_type, source, prompt, tags, created_at)
                    VALUES (?, ?, ?, 'IMAGE', ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE storage_provider = VALUES(storage_provider), object_key = VALUES(object_key),
                        mime_type = VALUES(mime_type), source = VALUES(source), prompt = VALUES(prompt),
                        tags = VALUES(tags), updated_at = CURRENT_TIMESTAMP
                    """, userId, image.assetId(), image.version(), safe(storageProvider), image.file().toString(),
                    safe(image.mediaType()), safe(image.source()), safe(image.prompt()), safe(image.tags()),
                    java.sql.Timestamp.from(image.createdAt()));
        } catch (RuntimeException exception) {
            log.warn("Could not persist image asset metadata, user={}", Integer.toHexString(userId.hashCode()), exception);
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
