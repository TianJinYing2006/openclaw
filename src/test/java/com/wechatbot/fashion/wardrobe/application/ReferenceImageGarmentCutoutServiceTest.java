package com.wechatbot.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechatbot.fashion.ai.service.AiImageGenerationService;
import com.wechatbot.fashion.ai.service.LocalImageAssetStore;
import com.wechatbot.fashion.ai.service.LocalImageAssetStore.StoredImage;
import com.wechatbot.fashion.wardrobe.domain.ClothingCandidate;
import com.wechatbot.fashion.wardrobe.domain.ClothingCandidateStatus;
import com.wechatbot.fashion.wardrobe.domain.ClothingCompletenessStatus;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceImageGarmentCutoutServiceTest {

    @Test
    void revisionUsesOnlyTheSelectedDraftAsItsReference() {
        AiImageGenerationService images = mock(AiImageGenerationService.class);
        LocalImageAssetStore assets = mock(LocalImageAssetStore.class);
        ReferenceImageGarmentCutoutService service = new ReferenceImageGarmentCutoutService(images, assets);
        StoredImage draft = image("img_draft");
        when(assets.signedReadUrl(draft)).thenReturn("https://oss.example/draft");
        when(images.revise(eq("wechat-user"), contains("supplied draft as the only reference"),
                eq("https://oss.example/draft")))
                .thenReturn(AiImageGenerationService.Result.image(new byte[] {1, 2, 3}));

        var result = service.revise("wechat-user", draft, candidate(), "衣长加长一点");

        assertThat(result.hasImage()).isTrue();
        verify(images).revise(eq("wechat-user"), contains("Preserve every existing print"),
                eq("https://oss.example/draft"));
    }

    private static StoredImage image(String assetId) {
        return new StoredImage(assetId, 1, Path.of(assetId + ".png"), "", null, Instant.now(), "image/png", "generated", "");
    }

    private static ClothingCandidate candidate() {
        Instant now = Instant.now();
        return new ClothingCandidate("candidate", 1L, "instance", 2L, 0, "灰色字母印花宽松短袖T恤",
                "T_SHIRT", "GRAY", List.of(), List.of("CASUAL"), "RELAXED", List.of("SUMMER"),
                "{\"patternCode\":\"PRINTED\"}", new BigDecimal("0.9"), new BigDecimal("0.8"),
                ClothingCompletenessStatus.READY, "", ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION,
                3L, null, "test", "test", "fashion-v2", now.plusSeconds(600), now, now);
    }
}
