package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.FashionPersonTemplate;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Resolves user-owned fashion images into bytes only after the owning record has been validated. */
@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class FashionVisualPreviewService {
    private final FashionCoreService wardrobe;
    private final FashionPersonTemplateService templates;
    private final LocalImageAssetStore imageStore;

    public FashionVisualPreviewService(FashionCoreService wardrobe, FashionPersonTemplateService templates,
                                       LocalImageAssetStore imageStore) {
        this.wardrobe = wardrobe;
        this.templates = templates;
        this.imageStore = imageStore;
    }

    public Optional<TemplatePreview> currentTemplate(String externalUserId) {
        return templates.active(externalUserId)
                .flatMap(template -> imageStore.find(externalUserId, template.sourceAssetId(), template.sourceAssetVersion())
                        .map(image -> templatePreview(template, image)));
    }

    public List<WardrobePreview> wardrobeItems(String externalUserId, WardrobeSearchCriteria criteria, int limit) {
        return wardrobe.searchWardrobeItems(externalUserId, criteria, limit).stream()
                .map(item -> wardrobePreview(externalUserId, item))
                .toList();
    }

    private TemplatePreview templatePreview(FashionPersonTemplate template, StoredImage image) {
        return new TemplatePreview(template.displayName(), image.assetId(), image.version(), imageStore.readBytes(image));
    }

    private WardrobePreview wardrobePreview(String externalUserId, WardrobeItem item) {
        Optional<StoredImage> image = wardrobe.primaryWardrobeImage(externalUserId, item.id())
                .flatMap(asset -> imageStore.find(externalUserId, asset.assetId(), asset.version()));
        return image.map(value -> new WardrobePreview(item, value.assetId(), value.version(), imageStore.readBytes(value)))
                .orElseGet(() -> new WardrobePreview(item, "", 0, null));
    }

    public record TemplatePreview(String displayName, String assetId, int version, byte[] imageBytes) {
        public TemplatePreview { imageBytes = imageBytes == null ? null : imageBytes.clone(); }
        @Override public byte[] imageBytes() { return imageBytes == null ? null : imageBytes.clone(); }
    }

    public record WardrobePreview(WardrobeItem item, String assetId, int version, byte[] imageBytes) {
        public WardrobePreview { imageBytes = imageBytes == null ? null : imageBytes.clone(); }
        @Override public byte[] imageBytes() { return imageBytes == null ? null : imageBytes.clone(); }
        public boolean hasImage() { return imageBytes != null && imageBytes.length > 0; }
    }
}
