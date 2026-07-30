package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import java.util.List;
import org.springframework.stereotype.Service;

/** First provider adapter: uses the current reference-image editing endpoint behind the cutout port. */
@Service
public class ReferenceImageGarmentCutoutService implements GarmentCutoutService {
    private final AiImageGenerationService images;
    private final LocalImageAssetStore assets;

    public ReferenceImageGarmentCutoutService(AiImageGenerationService images, LocalImageAssetStore assets) {
        this.images = images;
        this.assets = assets;
    }

    @Override
    public CutoutResult cutout(String externalUserId, StoredImage source, ClothingCandidate candidate, String instruction) {
        if (source == null || candidate == null) return CutoutResult.failed("Source image or candidate is missing");
        String prompt = "Create a clean standalone product image from the referenced clothing photo. Preserve only the selected garment "
                + "and its visible silhouette, color, texture, pattern, seams, proportions, and all original visual details. "
                + "Preserve any original print, lettering, graphic, logo, symbol, or decorative mark exactly as visible on the garment. "
                + "Remove people, other garments, hands, hangers, backgrounds, and unrelated objects. Remove only text, logos, or watermarks "
                + "newly invented by the model; never remove, replace, or redraw the garment's original print or lettering. Use a simple neutral light background. "
                + "When a small edge is hidden by the wearer or another garment, restore only a plain, category-consistent continuation "
                + "needed for a coherent display; do not invent logos, prints, pockets, trims, unusual proportions, or new details. "
                + "Selected garment: " + candidate.displayName()
                + "; category: " + candidate.categoryCode() + "; color: " + candidate.colorPrimary()
                + "; user refinement: " + safe(instruction);
        AiImageGenerationService.Result result = images.revise(externalUserId, prompt, assets.signedReadUrl(source));
        return result.hasImage() ? CutoutResult.image(result.imageBytes(), result.remoteUrl())
                : CutoutResult.failed(result.errorMessage());
    }

    @Override
    public CutoutResult revise(String externalUserId, StoredImage source, ClothingCandidate candidate, String instruction) {
        return revise(externalUserId, source, null, candidate, instruction);
    }

    @Override
    public CutoutResult revise(String externalUserId, StoredImage draft, StoredImage original,
                               ClothingCandidate candidate, String instruction) {
        if (draft == null || candidate == null) return CutoutResult.failed("Garment draft image or candidate is missing");
        String prompt = "Edit the standalone garment draft. Keep its category, garment identity, color, fabric, pattern, seams, texture, "
                + "lighting, simple neutral background, and every detail not explicitly changed. "
                + (original == null
                ? "Use the supplied draft as the only reference. "
                : "Reference image 1 is the original worn photo and is the factual source of truth for the garment's original print, lettering, "
                        + "graphics, logo, color, seams, and texture. Reference image 2 is the standalone draft whose framing and neutral background must remain. ")
                + "Never invent, replace, or add lettering, logos, watermarks, patterns, or graphics. Preserve any original print exactly as it appears in the original photo. "
                + "Apply only this user-requested visual adjustment: " + safe(instruction)
                + ". Do not add people, accessories, logos, text, hangers, a new garment, or unrelated objects. "
                + "Garment context: " + candidate.displayName() + "; category: " + candidate.categoryCode()
                + "; color: " + candidate.colorPrimary() + ".";
        List<String> references = original == null
                ? List.of(assets.signedReadUrl(draft))
                : List.of(assets.signedReadUrl(original), assets.signedReadUrl(draft));
        AiImageGenerationService.Result result = images.revise(externalUserId, prompt, references);
        return result.hasImage() ? CutoutResult.image(result.imageBytes(), result.remoteUrl())
                : CutoutResult.failed(result.errorMessage());
    }

    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
