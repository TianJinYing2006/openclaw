package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCompletenessStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Explicit, local-only probe for a user's existing OSS asset. It proves that the durable database record and private
 * OSS bytes can be read together, then evaluates the current worn-garment intake prompt. It never runs in CI.
 */
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.admin.enabled=false",
                "oss.image.enabled=true"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnabledIfEnvironmentVariable(named = "FASHION_OSS_HISTORY_LIVE_TEST", matches = "true")
class FashionOssHistoryLiveProbeTest {
    private static final String ASSET_ID_ENV = "FASHION_OSS_ASSET_ID";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LocalImageAssetStore images;

    @Autowired
    private FashionVisionCandidateAnalyzer analyzer;

    @Test
    @Timeout(90)
    void readsStoredImageAndFindsAtLeastOneUsableWornGarment() {
        String assetId = requiredEnvironment(ASSET_ID_ENV);
        String owner = jdbc.queryForObject(
                "SELECT external_user_id FROM asset_versions WHERE asset_id = ? AND asset_kind = 'IMAGE' "
                        + "ORDER BY version DESC LIMIT 1",
                String.class, assetId);
        assertThat(owner).isNotBlank();

        LocalImageAssetStore.StoredImage image = images.latest(owner, assetId)
                .orElseThrow(() -> new IllegalStateException("Image metadata exists but OSS image bytes are unavailable"));
        byte[] bytes = images.readBytes(image);
        assertThat(bytes).isNotEmpty();

        long started = System.nanoTime();
        WardrobePhotoAnalyzer.AnalysisResult result = analyzer.analyze(image);
        long analysisMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.printf("FASHION_OSS_HISTORY_PROBE asset=%s bytes=%d analysisMs=%d candidates=%s%n", assetId, bytes.length, analysisMs,
                result.candidates().stream().map(candidate -> candidate.categoryCode() + ':'
                        + candidate.completenessStatus() + ':' + candidate.qualityScore()).toList());
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).anyMatch(candidate -> candidate.completenessStatus() == ClothingCompletenessStatus.READY);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for the local live probe");
        return value.strip();
    }
}
