package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.ai.tool.ImageTaskRunner;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidateStatus;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCompletenessStatus;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidateDraft;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.wardrobe.persistence.FashionWardrobeIngestionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class FashionWardrobeIngestionServiceAsyncAnalysisTest {

    private final FashionWardrobeIngestionRepository repository = mock(FashionWardrobeIngestionRepository.class);
    private final FashionCoreService fashion = mock(FashionCoreService.class);
    private final WardrobePhotoAnalyzer analyzer = mock(WardrobePhotoAnalyzer.class);
    private final GarmentCutoutService cutouts = mock(GarmentCutoutService.class);
    private final LocalImageAssetStore imageStore = mock(LocalImageAssetStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsFalseAndSkipsBackgroundTaskWhenCandidatesAlreadyExist() {
        FashionWardrobeIngestionService service = new FashionWardrobeIngestionService(
                repository, fashion, analyzer, cutouts, imageStore, objectMapper);
        FashionImageAsset source = image(7L);
        when(repository.requireOwnedImage(eq("user-a"), eq("img_x"), eq(1))).thenReturn(source);
        when(repository.candidatesForSource("user-a", 7L))
                .thenReturn(List.of(candidate("c-1")));

        boolean submitted = service.submitPhotoAnalysis("user-a", "img_x", 1);

        assertThat(submitted).isFalse();
        verify(repository, never()).createCandidateDrafts(any(), any(), anyList(), any(), any(), any(), any());
    }

    @Test
    void runsBackgroundAnalysisAndPublishesCompletionEvent() {
        AtomicReference<Runnable> captured = new AtomicReference<>();
        ImageTaskRunner runner = task -> {
            captured.set(task);
            return true;
        };
        FashionWardrobeIngestionService service = new FashionWardrobeIngestionService(
                repository, fashion, analyzer, cutouts, imageStore, objectMapper);
        service.setAnalysisRunner(runner);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        service.setCompletionPublisher(publisher);
        FashionImageAsset source = image(8L);
        StoredImage stored = new StoredImage("img_x", 1, Path.of("x.png"), "", null,
                Instant.now(), "image/jpeg", "uploaded", "summary");
        when(repository.requireOwnedImage("user-a", "img_x", 1)).thenReturn(source);
        when(repository.candidatesForSource("user-a", 8L)).thenReturn(List.of());
        when(imageStore.find("user-a", "img_x", 1)).thenReturn(java.util.Optional.of(stored));
        ClothingCandidateDraft draft = new ClothingCandidateDraft(0, "整套穿搭", "OUTFIT", "BLUE", List.of(),
                List.of("CASUAL"), "REGULAR", List.of("SUMMER"), "{}", new BigDecimal("0.8"),
                new BigDecimal("0.7"), ClothingCompletenessStatus.READY, "");
        when(analyzer.analyze(stored))
                .thenReturn(new WardrobePhotoAnalyzer.AnalysisResult("识别为一整套", List.of(draft)));
        when(repository.createCandidateDrafts(eq("user-a"), eq(source), eq(List.of(draft)), any(), any(), any(), any()))
                .thenReturn(List.of(candidate("c-outfit")));

        boolean submitted = service.submitPhotoAnalysis("user-a", "img_x", 1);

        assertThat(submitted).isTrue();
        assertThat(captured.get()).isNotNull();
        captured.get().run();
        verify(repository).createCandidateDrafts(eq("user-a"), eq(source), eq(List.of(draft)), any(), any(), any(), any());
        verify(publisher).publishEvent(any(com.example.ykdsummer.fashion.wardrobe.runtime.FashionWardrobePhotoAnalyzedEvent.class));
    }

    @Test
    void deduplicatesConcurrentAnalysisForTheSamePhoto() {
        java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        ImageTaskRunner runner = task -> {
            submissions.incrementAndGet();
            return true;
        };
        FashionWardrobeIngestionService service = new FashionWardrobeIngestionService(
                repository, fashion, analyzer, cutouts, imageStore, objectMapper);
        service.setAnalysisRunner(runner);
        FashionImageAsset source = image(9L);
        when(repository.requireOwnedImage("user-a", "img_x", 1)).thenReturn(source);
        when(repository.candidatesForSource("user-a", 9L)).thenReturn(List.of());

        boolean first = service.submitPhotoAnalysis("user-a", "img_x", 1);
        boolean second = service.submitPhotoAnalysis("user-a", "img_x", 1);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        // 只有第一次真正提交了任务，第二次直接复用进行中的分析
        assertThat(submissions.get()).isEqualTo(1);
    }

    private static FashionImageAsset image(long versionId) {
        return new FashionImageAsset(versionId, "img_x", 1, "image/jpeg");
    }

    private static ClothingCandidate candidate(String id) {
        return new ClothingCandidate(id, 1L, "instance", 8L, 0, "整套穿搭", "OUTFIT", "BLUE", List.of(),
                List.of("CASUAL"), "REGULAR", List.of("SUMMER"), "{}", new BigDecimal("0.8"), new BigDecimal("0.7"),
                ClothingCompletenessStatus.READY, "", ClothingCandidateStatus.PENDING_SELECTION,
                null, null, "mcp", "qwen-vl-max", "mcp-v2", Instant.now().plusSeconds(600), Instant.now(), Instant.now());
    }
}
