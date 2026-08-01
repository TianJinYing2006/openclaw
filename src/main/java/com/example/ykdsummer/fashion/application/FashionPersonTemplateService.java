package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.FashionPersonTemplate;
import com.example.ykdsummer.fashion.domain.PersonTemplateAssessment;
import com.example.ykdsummer.fashion.persistence.FashionPersonTemplateRepository;
import com.example.ykdsummer.fashion.persistence.FashionWardrobeIngestionRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Coordinates source-image ownership, quality validation, and active virtual try-on template selection. */
@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class FashionPersonTemplateService {
    private final FashionPersonTemplateRepository templates;
    private final FashionWardrobeIngestionRepository wardrobeImages;
    private final FashionPersonTemplateAnalyzer analyzer;
    private final LocalImageAssetStore imageStore;

    public FashionPersonTemplateService(FashionPersonTemplateRepository templates,
                                        FashionWardrobeIngestionRepository wardrobeImages,
                                        FashionPersonTemplateAnalyzer analyzer,
                                        LocalImageAssetStore imageStore) {
        this.templates = templates;
        this.wardrobeImages = wardrobeImages;
        this.analyzer = analyzer;
        this.imageStore = imageStore;
    }

    public SaveResult saveFromPhoto(String externalUserId, String imageAssetId, Integer imageVersion, String displayName) {
        FashionImageAsset source = wardrobeImages.requireOwnedImage(externalUserId, imageAssetId, imageVersion);
        StoredImage stored = imageStore.find(externalUserId, source.assetId(), source.version())
                .orElseThrow(() -> new IllegalStateException("Template image bytes are not available"));
        PersonTemplateAssessment assessment = analyzer.analyze(stored);
        if (!assessment.ready()) return new SaveResult(null, assessment);
        return new SaveResult(templates.saveActive(externalUserId, source, displayName, assessment), assessment);
    }

    public List<FashionPersonTemplate> list(String externalUserId, int limit) { return templates.list(externalUserId, limit); }
    public Optional<FashionPersonTemplate> active(String externalUserId) { return templates.active(externalUserId); }
    public FashionPersonTemplate activate(String externalUserId, String templateId) {
        return templates.activate(externalUserId, templateId);
    }

    public record SaveResult(FashionPersonTemplate template, PersonTemplateAssessment assessment) {
        public boolean saved() { return template != null; }
    }
}
