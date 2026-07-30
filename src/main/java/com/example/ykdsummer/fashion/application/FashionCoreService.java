package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.domain.ClothingAnalysis;
import com.example.ykdsummer.fashion.domain.ClothingAnalysisDraft;
import com.example.ykdsummer.fashion.domain.FashionPreferenceUpdate;
import com.example.ykdsummer.fashion.domain.FashionProfileUpdate;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.FashionUserPreference;
import com.example.ykdsummer.fashion.domain.FashionUserProfile;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeItemDraft;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import com.example.ykdsummer.fashion.persistence.FashionCoreRepository;
import com.example.ykdsummer.fashion.persistence.FashionSemanticIndexJobRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application boundary for future Fashion Tools. It intentionally has no LLM or iLink dependency. */
@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class FashionCoreService {
    private final FashionCoreRepository repository;
    private final FashionSemanticIndexJobRepository semanticJobs;

    public FashionCoreService(FashionCoreRepository repository) {
        this.repository = repository;
        this.semanticJobs = null;
    }

    @Autowired
    public FashionCoreService(
            FashionCoreRepository repository,
            ObjectProvider<FashionSemanticIndexJobRepository> semanticJobs
    ) {
        this.repository = repository;
        this.semanticJobs = semanticJobs.getIfAvailable();
    }

    public FashionUserProfile profile(String externalUserId) { return repository.profile(externalUserId); }
    public FashionUserProfile saveProfile(String externalUserId, FashionProfileUpdate update) { return repository.saveProfile(externalUserId, update); }
    public FashionUserPreference updatePreference(String externalUserId, FashionPreferenceUpdate update) { return repository.upsertPreference(externalUserId, update); }
    public List<FashionUserPreference> preferences(String externalUserId) { return repository.preferences(externalUserId); }
    @Transactional
    public WardrobeItem addWardrobeItem(String externalUserId, WardrobeItemDraft draft) {
        WardrobeItem item = repository.createWardrobeItem(externalUserId, draft);
        enqueueSemanticIndex(item.id());
        return item;
    }

    /** Creates a garment and its ownership-checked image link in one database transaction. */
    @Transactional
    public WardrobeItem addWardrobeItemWithImage(
            String externalUserId, WardrobeItemDraft draft, String imageAssetId, Integer imageVersion
    ) {
        long assetVersionId = repository.findOwnedImageAssetVersion(externalUserId, imageAssetId, imageVersion)
                .orElseThrow(() -> new IllegalArgumentException("Image asset is not available to the current user"));
        WardrobeItem item = repository.createWardrobeItem(externalUserId, draft);
        repository.attachWardrobeAsset(externalUserId, item.id(), assetVersionId, "PRIMARY", true);
        enqueueSemanticIndex(item.id());
        return item;
    }
    public void attachWardrobeAsset(String externalUserId, long itemId, long assetVersionId, String role, boolean primary) {
        repository.attachWardrobeAsset(externalUserId, itemId, assetVersionId, role, primary);
    }
    public ClothingAnalysis saveClothingAnalysis(String externalUserId, long assetVersionId, ClothingAnalysisDraft draft) {
        return repository.recordAnalysis(externalUserId, assetVersionId, draft);
    }
    public List<WardrobeItem> activeWardrobeItems(String externalUserId, int limit) {
        return repository.activeWardrobeItems(externalUserId, limit);
    }
    public Optional<FashionImageAsset> primaryWardrobeImage(String externalUserId, long wardrobeItemId) {
        return repository.primaryWardrobeImage(externalUserId, wardrobeItemId);
    }

    /** Personal wardrobes stay small enough for in-process structured filtering; product catalog search remains separate. */
    public List<WardrobeItem> searchWardrobeItems(String externalUserId, WardrobeSearchCriteria criteria, int limit) {
        WardrobeSearchCriteria effective = criteria == null
                ? WardrobeSearchCriteria.from(null, null, List.of(), null, null, List.of(), List.of(), null)
                : criteria;
        int boundedLimit = Math.max(1, Math.min(limit, 20));
        int sourceLimit = effective.hasFilters() ? 1000 : boundedLimit;
        return repository.activeWardrobeItems(externalUserId, sourceLimit).stream()
                .filter(effective::matches)
                .limit(boundedLimit)
                .toList();
    }

    private void enqueueSemanticIndex(long wardrobeItemId) {
        if (semanticJobs != null) semanticJobs.enqueueUpsert(wardrobeItemId);
    }
}
