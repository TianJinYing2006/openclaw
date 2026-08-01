package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import java.time.Duration;

/** Replaceable provider port for rendering a saved garment on a saved person template. */
public interface VirtualTryOnService {
    TryOnResult render(String externalUserId, StoredImage personImage, StoredImage garmentImage,
                       String garmentCategoryCode, Duration timeout);

    record TryOnResult(byte[] imageBytes, String remoteUrl, String failureSummary) {
        public static TryOnResult image(byte[] imageBytes, String remoteUrl) {
            return new TryOnResult(imageBytes == null ? null : imageBytes.clone(), remoteUrl, null);
        }
        public static TryOnResult failed(String failureSummary) { return new TryOnResult(null, null, failureSummary); }
        public boolean hasImage() { return imageBytes != null && imageBytes.length > 0; }
        @Override public byte[] imageBytes() { return imageBytes == null ? null : imageBytes.clone(); }
    }
}
