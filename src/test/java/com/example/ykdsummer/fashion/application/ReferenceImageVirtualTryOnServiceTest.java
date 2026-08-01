package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReferenceImageVirtualTryOnServiceTest {

    @Test
    void submitsPersonAndGarmentAsTwoFixedReferencesWithTheBackgroundDeadline() {
        AiImageGenerationService images = mock(AiImageGenerationService.class);
        LocalImageAssetStore assets = mock(LocalImageAssetStore.class);
        StoredImage person = image("img_person", 1);
        StoredImage garment = image("img_garment", 2);
        when(assets.signedReadUrl(person)).thenReturn("https://oss.example/person");
        when(assets.signedReadUrl(garment)).thenReturn("https://oss.example/garment");
        when(images.revise(eq("user-a"), org.mockito.ArgumentMatchers.anyString(),
                eq(List.of("https://oss.example/person", "https://oss.example/garment")), eq(Duration.ofSeconds(150))))
                .thenReturn(AiImageGenerationService.Result.image(new byte[]{1, 2, 3}, "https://provider.example/result"));

        VirtualTryOnService.TryOnResult result = new ReferenceImageVirtualTryOnService(images, assets)
                .render("user-a", person, garment, "T_SHIRT", Duration.ofSeconds(150));

        assertThat(result.hasImage()).isTrue();
        assertThat(result.imageBytes()).containsExactly(1, 2, 3);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(images).revise(eq("user-a"), prompt.capture(),
                eq(List.of("https://oss.example/person", "https://oss.example/garment")), eq(Duration.ofSeconds(150)));
        assertThat(prompt.getValue()).contains("first reference image", "second reference image", "T_SHIRT");
    }

    private static StoredImage image(String assetId, int version) {
        return new StoredImage(assetId, version, Path.of("C:/test/" + assetId + ".png"), "", null,
                Instant.now(), "image/png", "generated", "");
    }
}
