package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.FashionTryOnTask;
import com.example.ykdsummer.fashion.domain.FashionTryOnTaskStatus;
import com.example.ykdsummer.fashion.domain.PersonTemplateAssessment;
import com.example.ykdsummer.fashion.domain.FashionPersonTemplateStatus;
import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.domain.ClothingCandidateStatus;
import com.example.ykdsummer.fashion.domain.ClothingCompletenessStatus;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeItemDraft;
import com.example.ykdsummer.fashion.persistence.FashionPersonTemplateRepository;
import com.example.ykdsummer.fashion.persistence.FashionWardrobeIngestionRepository;
import com.example.ykdsummer.persistence.ImageAssetMetadataStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Explicit end-to-end probe for the first virtual try-on adapter. It uses public images and a random synthetic user,
 * writes no OSS object, and removes its MySQL rows and local assets afterwards. The final preview is retained only
 * under target/ for visual inspection during this manual test run.
 */
@SpringBootTest(
        properties = {"ilink.enabled=false", "app.admin.enabled=false", "oss.image.enabled=false"},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnabledIfEnvironmentVariable(named = "FASHION_VIRTUAL_TRYON_LIVE_TEST", matches = "true")
class FashionVirtualTryOnLiveProbeTest {
    private static final String PERSON_URL = "https://images.pexels.com/photos/6520931/"
            + "pexels-photo-6520931.jpeg?auto=compress&cs=tinysrgb&w=900";
    private static final String GARMENT_URL = "https://qnam.smzdm.com/202106/10/60c18f1feccde7946.jpg_e1080.jpg";

    @Autowired private FashionVirtualTryOnService tryOn;
    @Autowired private FashionCoreService fashion;
    @Autowired private FashionWardrobeIngestionRepository images;
    @Autowired private FashionPersonTemplateRepository templates;
    @Autowired private GarmentCutoutService cutouts;
    @Autowired private LocalImageAssetStore imageStore;
    @Autowired private ImageAssetMetadataStore assetMetadata;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @Timeout(330)
    void rendersAnIsolatedStoredPublicGarmentOnAStoredPublicPersonTemplate() throws Exception {
        String externalUserId = "fashion-tryon-public-probe-" + UUID.randomUUID();
        StoredImage person = null;
        try {
            person = imageStore.saveIncoming(externalUserId, "public person template", download(PERSON_URL), "image/jpeg");
            StoredImage garment = imageStore.saveIncoming(externalUserId, "public garment source", download(GARMENT_URL), "image/jpeg");
            assetMetadata.record(externalUserId, person, "local");
            assetMetadata.record(externalUserId, garment, "local");
            FashionImageAsset personAsset = images.requireOwnedImage(externalUserId, person.assetId(), person.version());
            FashionImageAsset garmentSource = images.requireOwnedImage(externalUserId, garment.assetId(), garment.version());
            templates.saveActive(externalUserId, personAsset, "公开测试模板",
                    new PersonTemplateAssessment(FashionPersonTemplateStatus.READY, "public probe", "", java.math.BigDecimal.ONE));
            GarmentCutoutService.CutoutResult cutout = cutouts.cutout(externalUserId, garment, candidate(garmentSource), "");
            assertThat(cutout.hasImage()).as(cutout.failureSummary()).isTrue();
            StoredImage isolatedGarment = imageStore.saveGenerated(externalUserId, "public isolated garment",
                    cutout.imageBytes(), cutout.remoteUrl());
            assetMetadata.record(externalUserId, isolatedGarment, "local");
            WardrobeItem item = fashion.addWardrobeItemWithImage(externalUserId, new WardrobeItemDraft(
                    "T_SHIRT", "BLUE", List.of(), List.of("CASUAL"), "RELAXED", "SOLID",
                    List.of("SUMMER"), List.of("CASUAL"), "", "PUBLIC_PROBE", "", java.math.BigDecimal.ONE),
                    isolatedGarment.assetId(), isolatedGarment.version());

            FashionTryOnTask submitted = tryOn.submit(externalUserId, item.id());
            long started = System.nanoTime();
            tryOn.execute(submitted.id());
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            FashionTryOnTask completed = tryOn.latest(externalUserId).orElseThrow();
            assertThat(completed.status()).isEqualTo(FashionTryOnTaskStatus.SUCCEEDED);
            assertThat(completed.outputAssetVersionId()).isNotNull();
            String outputAssetId = jdbc.queryForObject("SELECT asset_id FROM asset_versions WHERE id = ?", String.class,
                    completed.outputAssetVersionId());
            Integer outputVersion = jdbc.queryForObject("SELECT version FROM asset_versions WHERE id = ?", Integer.class,
                    completed.outputAssetVersionId());
            StoredImage output = imageStore.find(externalUserId, outputAssetId, outputVersion).orElseThrow();
            Path preview = Path.of("target", "fashion-tryon-live-probe.png").toAbsolutePath().normalize();
            Files.createDirectories(preview.getParent());
            Files.write(preview, imageStore.readBytes(output));
            System.out.printf("FASHION_VIRTUAL_TRYON_PROBE durationMs=%d status=%s preview=%s%n",
                    durationMs, completed.status(), preview);
        } finally {
            cleanup(externalUserId, person);
        }
    }

    private static byte[] download(String sourceUrl) throws Exception {
        HttpResponse<byte[]> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().send(
                HttpRequest.newBuilder(URI.create(sourceUrl)).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "YkdSummerFashionTryOnProbe/1.0").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length == 0) {
            throw new IllegalStateException("Could not download public try-on probe image");
        }
        return response.body();
    }

    private static ClothingCandidate candidate(FashionImageAsset source) {
        java.time.Instant now = java.time.Instant.now();
        return new ClothingCandidate("public-cutout-candidate", 0L, "", source.assetVersionId(), 0,
                "blue casual short-sleeve top", "T_SHIRT", "BLUE", List.of(), List.of("CASUAL"), "RELAXED",
                List.of("SUMMER"), "{}", java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                ClothingCompletenessStatus.READY, "", ClothingCandidateStatus.CUTOUT_SUBMITTED,
                null, null, "public-probe", "", "", now.plusSeconds(600), now, now);
    }

    private void cleanup(String externalUserId, StoredImage source) throws Exception {
        jdbc.update("DELETE FROM fashion_virtual_tryon_tasks WHERE app_user_id IN (SELECT id FROM app_users WHERE external_user_id = ?)", externalUserId);
        jdbc.update("DELETE FROM fashion_person_templates WHERE app_user_id IN (SELECT id FROM app_users WHERE external_user_id = ?)", externalUserId);
        jdbc.update("""
                DELETE link FROM fashion_wardrobe_item_assets link
                JOIN fashion_wardrobe_items item ON item.id = link.wardrobe_item_id
                JOIN app_users user_record ON user_record.id = item.app_user_id
                WHERE user_record.external_user_id = ?
                """, externalUserId);
        jdbc.update("DELETE FROM fashion_wardrobe_items WHERE app_user_id IN (SELECT id FROM app_users WHERE external_user_id = ?)", externalUserId);
        jdbc.update("DELETE FROM asset_versions WHERE external_user_id = ?", externalUserId);
        jdbc.update("DELETE FROM app_users WHERE external_user_id = ?", externalUserId);
        if (source != null) {
            Path userDirectory = source.file().getParent().getParent();
            if (Files.exists(userDirectory)) {
                try (var paths = Files.walk(userDirectory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception exception) {
                            throw new IllegalStateException("Could not remove public probe files", exception);
                        }
                    });
                }
            }
        }
    }
}
