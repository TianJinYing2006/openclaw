package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import com.example.ykdsummer.fashion.persistence.FashionReferenceRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.fashion.reference", name = "enabled", havingValue = "true")
public class FashionReferenceService {
    private final FashionReferenceRepository repository;
    private final LocalImageAssetStore images;

    public FashionReferenceService(FashionReferenceRepository repository, LocalImageAssetStore images) {
        this.repository = repository;
        this.images = images;
    }

    public List<FashionReferenceLook> search(WardrobeSearchCriteria criteria, int limit) {
        WardrobeSearchCriteria effective = criteria == null
                ? WardrobeSearchCriteria.from(null, null, List.of(), null, null, List.of(), List.of(), null)
                : criteria;
        int bounded = Math.max(1, Math.min(limit, 20));
        return repository.activeLooks(effective.hasFilters() ? 2000 : bounded).stream()
                .filter(effective::matches).limit(bounded).toList();
    }

    public Optional<byte[]> image(FashionReferenceLook look) {
        if (look == null || look.imageAssetId().isBlank() || look.imageAssetVersion() < 1) return Optional.empty();
        return images.find(FashionReferenceImportService.PUBLIC_ASSET_OWNER, look.imageAssetId(), look.imageAssetVersion())
                .map(images::readBytes);
    }
}
