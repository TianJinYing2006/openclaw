package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.ClothingCandidate;

/** Provider boundary for generating a standalone garment display from an approved candidate. */
public interface GarmentCutoutService {
    CutoutResult cutout(String externalUserId, StoredImage source, ClothingCandidate candidate, String instruction);

    /** Edits an already isolated garment preview while preserving it as a separate selectable version. */
    default CutoutResult revise(String externalUserId, StoredImage source, ClothingCandidate candidate, String instruction) {
        return cutout(externalUserId, source, candidate, instruction);
    }

    record CutoutResult(byte[] imageBytes, String remoteUrl, String failureSummary) {
        public static CutoutResult image(byte[] bytes, String remoteUrl) { return new CutoutResult(bytes, remoteUrl, ""); }
        public static CutoutResult failed(String summary) { return new CutoutResult(null, null, summary == null ? "" : summary); }
        public boolean hasImage() { return imageBytes != null && imageBytes.length > 0; }
        @Override public byte[] imageBytes() { return imageBytes == null ? null : imageBytes.clone(); }
    }
}
