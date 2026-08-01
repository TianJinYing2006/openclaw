package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.domain.ClothingCandidateDraft;
import com.example.ykdsummer.fashion.domain.ClothingCandidateLabels;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.GarmentDraftVersion;
import com.example.ykdsummer.fashion.domain.GarmentCutoutTask;
import com.example.ykdsummer.fashion.domain.GarmentCutoutWork;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable workflow port for photo-to-wardrobe drafts. */
public interface FashionWardrobeIngestionRepository {
    FashionImageAsset requireOwnedImage(String externalUserId, String assetId, Integer version);
    List<ClothingCandidate> candidatesForSource(String externalUserId, long sourceAssetVersionId);
    List<ClothingCandidate> createCandidateDrafts(
            String externalUserId, FashionImageAsset source, List<ClothingCandidateDraft> drafts,
            String provider, String model, String promptVersion, Instant expiresAt
    );
    Optional<ClothingCandidate> candidate(String externalUserId, String candidateId);
    List<ClothingCandidate> pendingSelectionCandidates(String externalUserId);
    List<ClothingCandidate> awaitingFinalConfirmationCandidates(String externalUserId);
    List<ClothingCandidate> activeWorkflowCandidates(String externalUserId);
    int cancelCandidates(String externalUserId, List<String> candidateIds, Instant cancelledAt);
    List<GarmentDraftVersion> draftVersions(String externalUserId, String candidateId);
    ClothingCandidate updateLabels(String externalUserId, String candidateId, ClothingCandidateLabels labels);
    List<GarmentCutoutTask> submitCutoutTasks(String externalUserId, List<String> candidateIds, String instruction);
    GarmentCutoutTask retryCutoutTask(String externalUserId, String candidateId, String instruction, Instant expiresAt);
    GarmentCutoutTask reviseDraftTask(String externalUserId, String candidateId, int sourceVersionNumber,
                                      String instruction, Instant expiresAt);
    Optional<GarmentCutoutTask> latestCutoutTask(String externalUserId, String candidateId);
    List<String> pendingCutoutTaskIds(Instant now, int limit);
    Optional<GarmentCutoutWork> claimCutoutTask(String taskId, Instant now);
    void completeCutoutTask(String taskId, long outputAssetVersionId, Instant completedAt);
    void failCutoutTask(String taskId, String failureSummary, Instant completedAt);
    FashionImageAsset requireOwnedImageVersion(String externalUserId, long assetVersionId);
    void markCandidateConfirmed(String externalUserId, String candidateId, long selectedAssetVersionId,
                                long wardrobeItemId, Instant confirmedAt);

    /** Compatibility helper for callers that intentionally confirm the current preview. */
    default void markCandidateConfirmed(String externalUserId, String candidateId, long wardrobeItemId, Instant confirmedAt) {
        long selected = candidate(externalUserId, candidateId)
                .map(ClothingCandidate::currentCutoutAssetVersionId)
                .filter(value -> value != null)
                .orElseThrow(() -> new IllegalStateException("Candidate has no current garment draft"));
        markCandidateConfirmed(externalUserId, candidateId, selected, wardrobeItemId, confirmedAt);
    }
    void renewCandidateDraft(String externalUserId, String candidateId, Instant expiresAt);
    int expireUnconfirmedDrafts(Instant now);
}
