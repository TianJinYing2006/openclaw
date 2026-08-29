package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.wardrobe.config.FashionReferenceImportOptions;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.wardrobe.persistence.FashionReferenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** Opt-in probe for the real WET files. It writes only to a JUnit temporary asset directory. */
@EnabledIfSystemProperty(named = "fashion.reference.live-data", matches = "true")
class FashionReferenceDatasetLiveProbeTest {
    @TempDir Path temporaryDirectory;

    @Test
    void mapsTheFirstTwelveLooksToReadyTopAndBottomAssets() {
        Path annotation = requiredPath("fashion.reference.annotation-file");
        Path sourceImages = requiredPath("fashion.reference.image-directory");
        Path jobs = requiredPath("fashion.reference.cutout-job-file");
        Path cutouts = requiredPath("fashion.reference.cutout-image-directory");
        FashionReferenceRepository repository = mock(FashionReferenceRepository.class);
        when(repository.findByReferenceCode(anyString())).thenReturn(Optional.empty());
        when(repository.findBySha256(anyString())).thenReturn(Optional.empty());
        when(repository.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FashionReferenceImportService importer = new FashionReferenceImportService(repository,
                new LocalImageAssetStore(temporaryDirectory.resolve("assets")), new ObjectMapper());
        FashionReferenceImportOptions options = new FashionReferenceImportOptions(
                Set.of("TOP", "BOTTOM", "OUTERWEAR"), jobs, null, cutouts);

        var report = importer.importFile(annotation, sourceImages, 12, false, options);

        assertThat(report.imported()).isEqualTo(12);
        assertThat(report.failures()).isEmpty();
        ArgumentCaptor<FashionReferenceLook> saved = ArgumentCaptor.forClass(FashionReferenceLook.class);
        verify(repository, times(12)).upsert(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(look -> {
            assertThat(look.garments()).hasSize(2);
            assertThat(look.garments()).extracting(value -> value.categoryCode())
                    .containsExactly("TOP", "BOTTOM");
            assertThat(look.garments()).allSatisfy(garment -> {
                assertThat(garment.cutoutStatus()).isEqualTo("READY");
                assertThat(garment.cutoutAssetId()).startsWith("img_");
                assertThat(garment.cutoutSha256()).hasSize(64);
            });
            assertThat(look.annotationJson()).contains("SHOES");
        });
    }

    private static Path requiredPath(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return Path.of(value).toAbsolutePath().normalize();
    }
}
