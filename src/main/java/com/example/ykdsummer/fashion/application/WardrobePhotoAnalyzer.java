package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.ClothingCandidateDraft;
import java.util.List;

/**
 * Replaceable provider boundary for analyzing a wardrobe photo into structured clothing candidates.
 *
 * <p>Each implementation owns its vision call and parsing logic, but must return candidates that
 * obey the same business rules: category normalization, quality thresholds, and display-name
 * generation via {@link FashionItemNamer}.</p>
 */
public interface WardrobePhotoAnalyzer {

    /** Analyze the source image and return structured clothing candidate drafts. */
    AnalysisResult analyze(StoredImage source);

    /** Provider identifier persisted as candidate metadata (e.g. {@code "chat-completions-vision"}). */
    default String providerName() { return "unknown"; }

    /** Prompt or analysis version persisted as candidate metadata (e.g. {@code "fashion-wardrobe-v2"}). */
    default String promptVersion() { return "unknown"; }

    /** Structured analysis result before persistence. */
    record AnalysisResult(String summary, List<ClothingCandidateDraft> candidates) { }
}
