package com.example.ykdsummer.fashion.wardrobe.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingAnalysis;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingAnalysisDraft;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionPreferenceUpdate;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionProfileUpdate;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionUserPreference;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionUserProfile;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItem;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItemDraft;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeSearchCriteria;
import com.example.ykdsummer.fashion.wardrobe.persistence.FashionCoreRepository;
import com.example.ykdsummer.fashion.wardrobe.persistence.FashionSemanticIndexJobRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application boundary for future Fashion Tools. It intentionally has no LLM or iLink dependency. */
@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class FashionCoreService {
    private static final Logger log = LoggerFactory.getLogger(FashionCoreService.class);
    private final FashionCoreRepository repository;
    private final FashionSemanticIndexJobRepository semanticJobs;
    private volatile LocalImageAssetStore imageStore;

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

    @Autowired(required = false)
    public void setImageStore(LocalImageAssetStore imageStore) {
        this.imageStore = imageStore;
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

    /** 归档（软删除）衣橱单品；删除后同步使语义索引失效，避免已删除的单品被自然语言检索到。 */
    @Transactional
    public boolean archiveWardrobeItem(String externalUserId, long wardrobeItemId) {
        boolean changed = repository.archiveWardrobeItem(externalUserId, wardrobeItemId);
        if (changed && semanticJobs != null) semanticJobs.enqueueDelete(wardrobeItemId);
        return changed;
    }

    /**
     * 彻底删除衣橱单品（不可恢复）：引用预检后删除单品行与资产记录，
     * 并在数据库事务之外清理图片存储对象（OSS/本地）。存在试穿或推荐记录时抛异常拒绝。
     */
    public List<String> purgeWardrobeItem(String externalUserId, long wardrobeItemId) {
        List<String> purgedAssetIds = repository.purgeWardrobeItem(externalUserId, wardrobeItemId);
        if (semanticJobs != null) semanticJobs.enqueueDelete(wardrobeItemId);
        LocalImageAssetStore store = imageStore;
        if (store != null) {
            for (String assetId : purgedAssetIds) {
                try {
                    store.deleteAsset(externalUserId, assetId);
                } catch (RuntimeException failure) {
                    log.warn("Wardrobe purge: failed to delete asset {}, wardrobeItem={}: {}",
                            assetId, wardrobeItemId, failure.getMessage());
                }
            }
        }
        return purgedAssetIds;
    }
    public ClothingAnalysis saveClothingAnalysis(String externalUserId, long assetVersionId, ClothingAnalysisDraft draft) {
        return repository.recordAnalysis(externalUserId, assetVersionId, draft);
    }
    public List<WardrobeItem> activeWardrobeItems(String externalUserId, int limit) {
        return repository.activeWardrobeItems(externalUserId, limit);
    }
    public Optional<WardrobeItem> ownedWardrobeItem(String externalUserId, long wardrobeItemId) {
        return repository.findOwnedWardrobeItem(externalUserId, wardrobeItemId);
    }
    public Optional<FashionImageAsset> primaryWardrobeImage(String externalUserId, long wardrobeItemId) {
        return repository.primaryWardrobeImage(externalUserId, wardrobeItemId);
    }
    public Map<Long, FashionImageAsset> primaryWardrobeImages(String externalUserId, java.util.Collection<Long> itemIds) {
        return repository.primaryWardrobeImages(externalUserId, itemIds);
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
