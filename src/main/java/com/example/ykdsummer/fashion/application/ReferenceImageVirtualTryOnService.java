package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/** First adapter: the configured OpenAI-compatible image-edit endpoint receives person and garment references. */
@Service
public class ReferenceImageVirtualTryOnService implements VirtualTryOnService {
    private final AiImageGenerationService images;
    private final LocalImageAssetStore imageStore;

    public ReferenceImageVirtualTryOnService(AiImageGenerationService images, LocalImageAssetStore imageStore) {
        this.images = images;
        this.imageStore = imageStore;
    }

    @Override
    public TryOnResult render(String externalUserId, StoredImage personImage, StoredImage garmentImage,
                              String garmentCategoryCode, Duration timeout) {
        if (personImage == null || garmentImage == null) return TryOnResult.failed("试衣素材图片不可用");
        String prompt = """
                The first reference image is the person template. The second reference image is the selected standalone garment.
                Create one realistic full-body virtual try-on preview. Preserve the person's identity, face, pose, body proportions,
                background, camera angle, and lighting. Put only the referenced garment on the person with a natural fit and believable
                fabric drape. Keep unrelated garments and accessories unless the selected garment would naturally cover them. Do not add
                extra people, text, logos, watermarks, new accessories, or a collage. Garment category: %s.
                """.formatted(safe(garmentCategoryCode));
        AiImageGenerationService.Result result = images.revise(externalUserId, prompt, List.of(
                imageStore.signedReadUrl(personImage), imageStore.signedReadUrl(garmentImage)), timeout);
        return result.hasImage() ? TryOnResult.image(result.imageBytes(), result.remoteUrl())
                : TryOnResult.failed(result.errorMessage());
    }

    private static String safe(String value) { return value == null ? "UNKNOWN" : value.replace('\u0000', ' ').strip(); }
}
