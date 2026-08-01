package com.example.ykdsummer.persistence;

import com.example.ykdsummer.ai.config.OssImageProperties;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.ai.service.OssImageAssetStore;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore.StoredDocument;
import com.example.ykdsummer.bot.file.OssDocumentAssetStore;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** One-way, idempotent copy of legacy local assets. It never deletes or modifies the local source directories. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnProperty(prefix = "oss.image", name = "enabled", havingValue = "true")
public class LegacyAssetOssMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyAssetOssMigration.class);
    private final OssImageProperties properties;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final OssImageAssetStore images;
    private final ObjectProvider<OssDocumentAssetStore> documentsProvider;
    private final ObjectProvider<ImageAssetMetadataStore> imageMetadataProvider;
    private final ObjectProvider<DocumentAssetMetadataStore> documentMetadataProvider;

    public LegacyAssetOssMigration(
            OssImageProperties properties,
            ObjectProvider<JdbcTemplate> jdbcProvider,
            OssImageAssetStore images,
            ObjectProvider<OssDocumentAssetStore> documentsProvider,
            ObjectProvider<ImageAssetMetadataStore> imageMetadataProvider,
            ObjectProvider<DocumentAssetMetadataStore> documentMetadataProvider
    ) {
        this.properties = properties;
        this.jdbcProvider = jdbcProvider;
        this.images = images;
        this.documentsProvider = documentsProvider;
        this.imageMetadataProvider = imageMetadataProvider;
        this.documentMetadataProvider = documentMetadataProvider;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments arguments) {
        if (!properties.isMigrateLocalAssets()) return;
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            log.warn("Skipping local asset migration because MySQL persistence is disabled");
            return;
        }
        Set<String> users = new LinkedHashSet<>(jdbc.query("""
                SELECT external_user_id FROM app_users WHERE external_user_id <> ''
                UNION SELECT external_user_id FROM chat_conversations WHERE external_user_id <> ''
                UNION SELECT external_user_id FROM asset_versions WHERE external_user_id <> ''
                """, (rs, ignored) -> rs.getString(1)));
        if (users.isEmpty()) return;

        LocalImageAssetStore localImages = new LocalImageAssetStore();
        LocalDocumentAssetStore localDocuments = new LocalDocumentAssetStore();
        ImageAssetMetadataStore imageMetadata = imageMetadataProvider.getIfAvailable(ImageAssetMetadataStore::disabled);
        DocumentAssetMetadataStore documentMetadata = documentMetadataProvider.getIfAvailable(DocumentAssetMetadataStore::disabled);
        OssDocumentAssetStore documents = documentsProvider.getIfAvailable();
        int imageVersions = 0;
        int documentVersions = 0;
        for (String userId : users) {
            imageVersions += migrateImages(userId, localImages, imageMetadata);
            if (documents != null) documentVersions += migrateDocuments(userId, localDocuments, documents, documentMetadata);
        }
        log.info("Legacy local asset migration completed, imageVersions={}, documentVersions={}, users={}",
                imageVersions, documentVersions, users.size());
    }

    private int migrateImages(String userId, LocalImageAssetStore local, ImageAssetMetadataStore metadata) {
        int copied = 0;
        for (StoredImage latest : local.recent(userId, 500)) {
            for (StoredImage version : local.versions(userId, latest.assetId())) {
                StoredImage migrated = images.importLegacy(userId, version, local.readBytes(version));
                metadata.record(userId, migrated, "oss");
                copied++;
            }
        }
        local.current(userId).ifPresent(current -> images.selectCurrent(userId, current.assetId(), current.version()));
        return copied;
    }

    private int migrateDocuments(String userId, LocalDocumentAssetStore local, OssDocumentAssetStore documents,
                                 DocumentAssetMetadataStore metadata) {
        int copied = 0;
        for (StoredDocument latest : local.recent(userId, 500)) {
            for (StoredDocument version : local.versions(userId, latest.assetId())) {
                StoredDocument migrated = documents.importLegacy(userId, version, local.readBytes(version));
                metadata.record(userId, migrated, "oss");
                copied++;
            }
        }
        local.current(userId).ifPresent(current -> documents.selectCurrent(userId, current.assetId(), current.version()));
        return copied;
    }
}
