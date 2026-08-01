package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.ClothingCandidate;
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
                + "and its visible silhouette, color, texture, pattern, seams, proportions, and all original visual details except changes "
                + "that the user explicitly requests below. "
                + "Preserve any original print, lettering, graphic, logo, symbol, or decorative mark exactly as visible on the garment. "
                + "Remove people, other garments, hands, hangers, backgrounds, and unrelated objects. Remove only text, logos, or watermarks "
                + "newly invented by the model; never remove, replace, or redraw the garment's original print or lettering. Use a simple neutral light background. "
                + "When a small edge is hidden by the wearer or another garment, restore only a plain, category-consistent continuation "
                + "needed for a coherent display; do not invent logos, prints, pockets, trims, unusual proportions, or new details. "
                + "If the user refinement is not blank, apply only that requested adjustment while extracting the garment. "
                + "Selected garment: " + candidate.displayName()
                + "; category: " + candidate.categoryCode() + "; color: " + candidate.colorPrimary()
                + "; user refinement: " + safe(instruction);
        AiImageGenerationService.Result result = images.revise(externalUserId, prompt, assets.signedReadUrl(source));
        return result.hasImage() ? CutoutResult.image(result.imageBytes(), result.remoteUrl())
                : CutoutResult.failed(result.errorMessage());
    }

    @Override
    public CutoutResult revise(String externalUserId, StoredImage draft, ClothingCandidate candidate, String instruction) {
        if (draft == null || candidate == null) return CutoutResult.failed("Garment draft image or candidate is missing");
        String prompt = "Edit the standalone garment draft. Keep its category, garment identity, color, fabric, pattern, seams, texture, "
                + "lighting, simple neutral background, and every detail not explicitly changed. "
                + "Use the supplied draft as the only reference. Never invent, replace, or add lettering, logos, watermarks, patterns, or graphics. "
                + "Preserve every existing print and visual detail exactly unless the user explicitly asks to change it. "
                + "Apply only this user-requested visual adjustment: " + safe(instruction)
                + ". Do not add people, accessories, logos, text, hangers, a new garment, or unrelated objects. "
                + "Garment context: " + candidate.displayName() + "; category: " + candidate.categoryCode()
                + "; color: " + candidate.colorPrimary() + ".";
        AiImageGenerationService.Result result = images.revise(externalUserId, prompt, assets.signedReadUrl(draft));
        return result.hasImage() ? CutoutResult.image(result.imageBytes(), result.remoteUrl())
                : CutoutResult.failed(result.errorMessage());
    }

    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
