package com.example.ykdsummer.fashion.wardrobe.persistence;

import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRecommendationRequest;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRecommendationResult;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRenderStatus;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRenderWork;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Durable boundary for recommendation snapshots and their background outfit-board work. */
public interface OutfitRecommendationRepository {
    OutfitRecommendationResult save(
            OutfitRecommendationRequest request,
            OutfitRecommendationResult result
    );
    Set<Long> recentlyRecommendedItemIds(String externalUserId, int limit);
    List<String> pendingRenderOptionIds(int limit);
    Optional<OutfitRenderWork> claimRender(String optionId, Instant claimedAt);
    void completeRender(String optionId, String outputAssetId, int outputVersion,
                        OutfitRenderStatus status, Instant completedAt);
    void failRender(String optionId, String failureSummary, Instant completedAt);
    Optional<OutfitRecommendationResult> latest(String externalUserId);
    int recoverInterruptedRenders();
}
