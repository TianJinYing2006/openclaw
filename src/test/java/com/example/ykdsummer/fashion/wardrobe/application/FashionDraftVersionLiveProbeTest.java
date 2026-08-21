package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCompletenessStatus;
import com.example.ykdsummer.fashion.wardrobe.domain.GarmentDraftVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Explicit manual probe for the full personal wardrobe draft flow. It creates only expiring drafts, never wardrobe items.
 */
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.admin.enabled=false"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnabledIfEnvironmentVariable(named = "FASHION_DRAFT_VERSION_LIVE_TEST", matches = "true")
class FashionDraftVersionLiveProbeTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FashionWardrobeIngestionService ingestion;

    @Autowired
    private LocalImageAssetStore images;

    @Test
    @Timeout(600)
    void createsThreeSelectableDraftVersionsFromOneOwnedPhoto() throws Exception {
        String assetId = requiredEnvironment("FASHION_DRAFT_PROBE_ASSET_ID");
        String owner = jdbc.queryForObject("""
                SELECT external_user_id FROM asset_versions
                WHERE asset_id = ? AND asset_kind = 'IMAGE'
                ORDER BY version DESC LIMIT 1
                """, String.class, assetId);
        assertThat(owner).isNotBlank();

        FashionWardrobeIngestionService.IntakeResult analysis = ingestion.analyzePhoto(owner, assetId, 1);
        ClothingCandidate candidate = analysis.candidates().stream()
                .filter(value -> value.completenessStatus() == ClothingCompletenessStatus.READY)
                .findFirst().orElseThrow(() -> new IllegalStateException("The probe image has no usable clothing candidate"));

        List<GarmentDraftVersion> versions = ingestion.draftVersions(owner, candidate.id());
        if (versions.isEmpty()) {
            var task = ingestion.selectCandidatesForCutout(owner, List.of(candidate.id())).getFirst();
            waitForVersions(owner, candidate.id(), task.id(), 1);
        }
        var second = ingestion.reviseDraft(owner, candidate.id(), null,
                "保持衣物身份、材质、颜色和简洁背景不变，仅让衣长自然加长一点，边缘完整。 ");
        waitForVersions(owner, candidate.id(), second.id(), 2);
        var third = ingestion.reviseDraft(owner, candidate.id(), 1,
                "保持第一版的衣物材质、颜色和版型不变，仅让整体展示更平整、下摆更自然完整。 ");
        List<GarmentDraftVersion> completed = waitForVersions(owner, candidate.id(), third.id(), 3);

        Path output = Path.of("target", "fashion-live-probe").toAbsolutePath().normalize();
        Files.createDirectories(output);
        for (GarmentDraftVersion version : completed) {
            LocalImageAssetStore.StoredImage image = ingestion.draftPreviewImage(owner, candidate.id(), version.versionNumber());
            Path file = output.resolve("draft-v" + version.versionNumber() + ".png");
            Files.write(file, images.readBytes(image));
            System.out.printf("FASHION_DRAFT_VERSION_PROBE version=%d current=%s asset=%s v%d file=%s%n",
                    version.versionNumber(), version.current(), image.assetId(), image.version(), file);
        }
        assertThat(completed).hasSize(3);
        assertThat(completed).anyMatch(GarmentDraftVersion::current);
    }

    private List<GarmentDraftVersion> waitForVersions(String owner, String candidateId, String taskId, int expected) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(8));
        while (Instant.now().isBefore(deadline)) {
            ingestion.executeCutoutTask(taskId);
            List<GarmentDraftVersion> values = ingestion.draftVersions(owner, candidateId);
            if (values.size() >= expected) return values;
            String state = jdbc.queryForObject("SELECT task_status FROM fashion_garment_cutout_tasks WHERE id = ?",
                    String.class, taskId);
            if ("FAILED".equals(state) || "EXPIRED".equals(state)) {
                String failure = jdbc.queryForObject("SELECT failure_summary FROM fashion_garment_cutout_tasks WHERE id = ?",
                        String.class, taskId);
                throw new IllegalStateException("Draft render failed: " + failure);
            }
            Thread.sleep(1_000);
        }
        throw new IllegalStateException("Timed out waiting for garment draft version " + expected);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for this local probe");
        return value.strip();
    }
}
