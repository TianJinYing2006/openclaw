package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionPersonTemplate;
import com.example.ykdsummer.persistence.ImageAssetMetadataStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Explicit manual probe for the already implemented person-template flow.
 *
 * <p>It deliberately uses a fresh synthetic user and a local image store, so a public probe image
 * never becomes a real user's active template or an OSS object. Normal builds never execute it.</p>
 */
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.admin.enabled=false",
                "oss.image.enabled=false"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnabledIfEnvironmentVariable(named = "FASHION_PERSON_TEMPLATE_LIVE_TEST", matches = "true")
class FashionPersonTemplateLiveProbeTest {
    private static final String DEFAULT_PUBLIC_MODEL_PHOTO = "https://images.pexels.com/photos/6520931/"
            + "pexels-photo-6520931.jpeg?auto=compress&cs=tinysrgb&w=900";

    @Autowired
    private FashionPersonTemplateService templates;

    @Autowired
    private LocalImageAssetStore imageStore;

    @Autowired
    private ImageAssetMetadataStore assetMetadata;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Timeout(180)
    void evaluatesAndPersistsThenCleansUpAPublicFullBodyTemplate() throws Exception {
        String externalUserId = "fashion-public-template-probe-" + UUID.randomUUID();
        StoredImage source = null;
        try {
            byte[] photo = downloadPublicModelPhoto();
            source = imageStore.saveIncoming(externalUserId, "public full-body person-template probe", photo, "image/jpeg");
            assetMetadata.record(externalUserId, source, "local");

            long started = System.nanoTime();
            FashionPersonTemplateService.SaveResult result = templates.saveFromPhoto(
                    externalUserId, source.assetId(), source.version(), "公开全身模特测试模板");
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            System.out.printf("FASHION_PERSON_TEMPLATE_PROBE durationMs=%d status=%s confidence=%s saved=%s summary=%s guidance=%s%n",
                    durationMs, result.assessment().status(), result.assessment().confidence(), result.saved(),
                    result.assessment().suitabilitySummary(), result.assessment().retakeGuidance());

            assertThat(result.assessment()).isNotNull();
            if (result.saved()) {
                Optional<FashionPersonTemplate> active = templates.active(externalUserId);
                assertThat(active).isPresent();
                assertThat(active.orElseThrow().sourceAssetId()).isEqualTo(source.assetId());
            }
        } finally {
            cleanup(externalUserId, source);
        }
    }

    private static byte[] downloadPublicModelPhoto() throws Exception {
        String sourceUrl = System.getenv().getOrDefault("FASHION_PERSON_TEMPLATE_PROBE_IMAGE_URL", DEFAULT_PUBLIC_MODEL_PHOTO).strip();
        HttpResponse<byte[]> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().send(
                HttpRequest.newBuilder(URI.create(sourceUrl)).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "YkdSummerFashionProbe/1.0").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length == 0) {
            throw new IllegalStateException("Could not download the public person-template probe image, status="
                    + response.statusCode());
        }
        return response.body();
    }

    private void cleanup(String externalUserId, StoredImage source) throws Exception {
        jdbc.update("DELETE FROM fashion_person_templates WHERE app_user_id IN (SELECT id FROM app_users WHERE external_user_id = ?)",
                externalUserId);
        jdbc.update("DELETE FROM asset_versions WHERE external_user_id = ?", externalUserId);
        jdbc.update("DELETE FROM app_users WHERE external_user_id = ?", externalUserId);
        if (source != null) {
            Path userDirectory = source.file().getParent().getParent();
            deleteTree(userDirectory);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception exception) {
                    throw new IllegalStateException("Could not remove template probe file", exception);
                }
            });
        }
    }
}
