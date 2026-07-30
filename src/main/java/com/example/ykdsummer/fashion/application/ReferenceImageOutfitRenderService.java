package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** First outfit-board adapter: the configured image-edit provider receives version-pinned OSS references. */
@Service
@ConditionalOnProperty(prefix = "app.fashion.outfit-recommendation", name = "render-provider",
        havingValue = "image-edit", matchIfMissing = true)
public class ReferenceImageOutfitRenderService implements OutfitRenderService {
    private final AiImageGenerationService images;
    private final LocalImageAssetStore imageStore;

    public ReferenceImageOutfitRenderService(
            AiImageGenerationService images,
            LocalImageAssetStore imageStore
    ) {
        this.images = images;
        this.imageStore = imageStore;
    }

    @Override
    public RenderResult render(String externalUserId, List<Source> sources, Duration timeout) {
        if (sources == null || sources.size() < 2) {
            return RenderResult.failed("搭配效果图至少需要两件单品");
        }
        String sourceOrder = java.util.stream.IntStream.range(0, sources.size())
                .mapToObj(index -> "Reference " + (index + 1) + ": " + safe(sources.get(index).role())
                        + " (" + safe(sources.get(index).displayName()) + ")")
                .collect(java.util.stream.Collectors.joining("\n"));
        String prompt = """
                Create one clean, premium, no-person outfit board using every referenced garment exactly once.
                Arrange the real garments as a coherent flat-lay outfit on a plain light neutral background.
                Preserve each garment's original category, silhouette, length, color, print, texture, logo placement,
                seams, buttons, pockets, and other visible details. Do not redesign, recolor, crop away, duplicate,
                replace, merge, or invent garments. Do not add a person, mannequin, shoes, accessories, text,
                labels, borders, watermarks, or unrelated objects. Keep clear spacing between garments.

                Reference order:
                %s
                """.formatted(sourceOrder);
        try {
            List<String> urls = sources.stream().map(Source::image)
                    .map(imageStore::signedReadUrl).toList();
            AiImageGenerationService.Result result = images.revise(externalUserId, prompt, urls, timeout);
            return result.hasImage() ? RenderResult.image(result.imageBytes(), result.remoteUrl())
                    : RenderResult.failed(result.errorMessage());
        } catch (RuntimeException failure) {
            return RenderResult.failed("搭配效果图服务暂时不可用");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').replace('\n', ' ').strip();
    }
}
