package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.FashionReferenceFixtures;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.persistence.FashionReferenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class FashionReferenceImportServiceTest {
    @TempDir Path directory;

    @Test
    void importsOneLookWithMultipleGarmentsAndKeepsTheFullAnnotation() throws Exception {
        Path images = Files.createDirectory(directory.resolve("images"));
        Files.write(images.resolve("look.png"), new byte[]{1, 2, 3, 4});
        Path json = directory.resolve("annotations.json");
        Files.writeString(json, """
                {"schemaVersion":"1.0.0","annotations":[{
                  "schemaVersion":"1.0.0","imageId":"look.png",
                  "imageAssessment":{"usable":true},
                  "garments":[
                    {"itemIndex":1,"displayName":"浅灰色宽松短袖T恤","categoryCode":"TOP",
                     "subCategoryCode":"T_SHIRT","targetGender":"UNISEX",
                     "colors":{"primaryCode":"LIGHT_GRAY","secondaryCodes":["WHITE"],"accentCodes":[]},
                     "patternCode":"SOLID","fitCode":"RELAXED","silhouetteCode":"H_LINE",
                     "lengthCode":"REGULAR","materialCodes":["COTTON_BLEND"],
                     "styleCodes":["MINIMAL","CASUAL"],"seasonCodes":["SUMMER"],
                     "occasionCodes":["DAILY"],"formalityLevel":1,
                     "visibility":{"visibilityStatus":"FULL","visibleRatio":0.95},
                     "confidence":{"overall":0.91}},
                    {"itemIndex":2,"displayName":"深蓝色直筒牛仔裤","categoryCode":"BOTTOM",
                     "subCategoryCode":"JEANS","targetGender":"UNISEX",
                     "colors":{"primaryCode":"DENIM_BLUE","secondaryCodes":[],"accentCodes":[]},
                     "patternCode":"SOLID","fitCode":"STRAIGHT","silhouetteCode":"STRAIGHT",
                     "lengthCode":"FULL_LENGTH","materialCodes":["DENIM"],
                     "styleCodes":["CASUAL"],"seasonCodes":["ALL_SEASON"],
                     "occasionCodes":["DAILY"],"formalityLevel":1,
                     "visibility":{"visibilityStatus":"FULL","visibleRatio":0.90},
                     "confidence":{"overall":0.94}}
                  ]}
                ]}
                """);
        FashionReferenceRepository repository = mock(FashionReferenceRepository.class);
        when(repository.findByReferenceCode("look.png")).thenReturn(Optional.empty());
        when(repository.findBySha256(any())).thenReturn(Optional.empty());
        when(repository.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FashionReferenceImportService service = new FashionReferenceImportService(repository,
                new LocalImageAssetStore(directory.resolve("assets")), new ObjectMapper());

        var report = service.importFile(json, images, 3, true);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.failures()).isEmpty();
        ArgumentCaptor<FashionReferenceLook> saved = ArgumentCaptor.forClass(FashionReferenceLook.class);
        verify(repository).upsert(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo("ACTIVE");
        assertThat(saved.getValue().usageRights()).isEqualTo("LOCAL_DEVELOPMENT_ONLY");
        assertThat(saved.getValue().garments()).hasSize(2);
        assertThat(saved.getValue().garments().getFirst().subCategoryCode()).isEqualTo("T_SHIRT");
        assertThat(saved.getValue().garments().get(1).materialTags()).containsExactly("DENIM");
        assertThat(saved.getValue().annotationJson()).contains("visibleRatio", "COTTON_BLEND");
        assertThat(saved.getValue().imageAssetId()).startsWith("img_");
        assertThat(saved.getValue().sha256()).hasSize(64);
    }

    @Test
    void rejectsUnsupportedSchemaWithoutWriting() throws Exception {
        Path images = Files.createDirectory(directory.resolve("images-invalid"));
        Files.write(images.resolve("look.png"), new byte[]{1});
        Path json = directory.resolve("invalid.json");
        Files.writeString(json, """
                {"annotations":[{"schemaVersion":"9.0.0","imageId":"look.png",
                "imageAssessment":{"usable":true},"garments":[{"displayName":"测试","subCategoryCode":"T_SHIRT"}]}]}
                """);
        FashionReferenceRepository repository = mock(FashionReferenceRepository.class);
        FashionReferenceImportService service = new FashionReferenceImportService(repository,
                new LocalImageAssetStore(directory.resolve("assets-invalid")), new ObjectMapper());

        var report = service.importFile(json, images, 3, true);

        assertThat(report.imported()).isZero();
        assertThat(report.failures()).singleElement().asString().contains("unsupported schemaVersion");
    }

    @Test
    void skipsTheSameImageWhenItAlreadyExistsUnderAnotherReferenceCode() throws Exception {
        Path images = Files.createDirectory(directory.resolve("images-duplicate"));
        Files.write(images.resolve("duplicate.png"), new byte[]{9, 8, 7});
        Path json = directory.resolve("duplicate.json");
        Files.writeString(json, """
                {"schemaVersion":"1.0.0","imageId":"duplicate.png",
                 "imageAssessment":{"usable":true},
                 "garments":[{"itemIndex":1,"displayName":"灰色短袖","categoryCode":"TOP",
                 "subCategoryCode":"T_SHIRT","colors":{"primaryCode":"GRAY"}}]}
                """);
        FashionReferenceRepository repository = mock(FashionReferenceRepository.class);
        when(repository.findByReferenceCode("duplicate.png")).thenReturn(Optional.empty());
        when(repository.findBySha256(any())).thenReturn(Optional.of(FashionReferenceFixtures.look()));
        FashionReferenceImportService service = new FashionReferenceImportService(repository,
                new LocalImageAssetStore(directory.resolve("assets-duplicate")), new ObjectMapper());

        var report = service.importFile(json, images, 3, true);

        assertThat(report.imported()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failures()).isEmpty();
        verify(repository, org.mockito.Mockito.never()).upsert(any());
    }
}
