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
}
