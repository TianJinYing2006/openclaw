package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.ClothingAnalysis;
import com.example.ykdsummer.fashion.domain.ClothingAnalysisDraft;
import com.example.ykdsummer.fashion.domain.FashionPreferenceUpdate;
import com.example.ykdsummer.fashion.domain.FashionProfileUpdate;
import com.example.ykdsummer.fashion.domain.FashionUserPreference;
import com.example.ykdsummer.fashion.domain.FashionUserProfile;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeItemDraft;
import com.example.ykdsummer.fashion.identity.FashionUserScope;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence port for the first Fashion vertical slice. */
public interface FashionCoreRepository {
    FashionUserScope resolveUser(String externalUserId);
    FashionUserProfile profile(String externalUserId);
    FashionUserProfile saveProfile(String externalUserId, FashionProfileUpdate update);
    FashionUserPreference upsertPreference(String externalUserId, FashionPreferenceUpdate update);
    List<FashionUserPreference> preferences(String externalUserId);
    WardrobeItem createWardrobeItem(String externalUserId, WardrobeItemDraft draft);
    void attachWardrobeAsset(String externalUserId, long wardrobeItemId, long assetVersionId, String assetRole, boolean primary);
    Optional<Long> findOwnedImageAssetVersion(String externalUserId, String assetId, Integer version);
    ClothingAnalysis recordAnalysis(String externalUserId, long assetVersionId, ClothingAnalysisDraft draft);
    List<WardrobeItem> activeWardrobeItems(String externalUserId, int limit);
    Optional<WardrobeItem> findWardrobeItemById(long wardrobeItemId);
    Optional<WardrobeItem> findOwnedWardrobeItem(String externalUserId, long wardrobeItemId);
    Optional<FashionImageAsset> primaryWardrobeImage(String externalUserId, long wardrobeItemId);
    Map<Long, FashionImageAsset> primaryWardrobeImages(String externalUserId, Collection<Long> wardrobeItemIds);

    /**
     * 归档（软删除）衣橱单品：把 {@code item_status} 从 ACTIVE 置为 ARCHIVED，
     * 使其从衣橱展示、搜索与语义检索中消失。返回是否真正发生了状态变更
     * （已是 ARCHIVED 或已不存在时返回 false）。
     */
    boolean archiveWardrobeItem(String externalUserId, long wardrobeItemId);

    /**
     * 彻底删除衣橱单品（不可恢复）：
     * 预检试穿/推荐记录引用（有引用则抛 IllegalArgumentException 拒绝），
     * 删除单品行（级联删资产关联行），并对不再被任何引用使用的图片资产删除 asset_versions 行。
     * 返回被清理的 assetId 列表，供上层删除存储对象（OSS/本地）。
     */
    List<String> purgeWardrobeItem(String externalUserId, long wardrobeItemId);
}
