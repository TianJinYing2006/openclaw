package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import java.time.Duration;
import java.util.List;

/** Replaceable provider port for rendering several user-owned garment images into one outfit board. */
public interface OutfitRenderService {
    RenderResult render(String externalUserId, List<Source> sources, Duration timeout);

    record Source(String role, String displayName, StoredImage image) { }

    record RenderResult(byte[] imageBytes, String remoteUrl, String failureSummary) {
        public static RenderResult image(byte[] bytes, String remoteUrl) {
            return new RenderResult(bytes == null ? null : bytes.clone(), remoteUrl, "");
        }
        public static RenderResult failed(String summary) {
            return new RenderResult(null, null, summary == null ? "" : summary);
        }
        public boolean hasImage() { return imageBytes != null && imageBytes.length > 0; }
        @Override public byte[] imageBytes() { return imageBytes == null ? null : imageBytes.clone(); }
    }
}
