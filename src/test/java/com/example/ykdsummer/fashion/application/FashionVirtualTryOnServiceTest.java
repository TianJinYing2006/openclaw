package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.config.FashionTryOnProperties;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.FashionTryOnTask;
import com.example.ykdsummer.fashion.domain.FashionTryOnTaskStatus;
import com.example.ykdsummer.fashion.domain.FashionTryOnWork;
import com.example.ykdsummer.fashion.persistence.FashionTryOnRepository;
import com.example.ykdsummer.fashion.runtime.FashionTryOnCompletedEvent;
import com.example.ykdsummer.persistence.ImageAssetMetadataStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class FashionVirtualTryOnServiceTest {

    @Test
    void persistsAndPublishesACompletedBackgroundTryOnUsingVersionPinnedAssets() {
        FashionTryOnRepository tasks = mock(FashionTryOnRepository.class);
        VirtualTryOnService renderer = mock(VirtualTryOnService.class);
        LocalImageAssetStore assets = mock(LocalImageAssetStore.class);
        FashionTryOnProperties properties = new FashionTryOnProperties();
        properties.setProviderTimeout(Duration.ofSeconds(150));
        FashionVirtualTryOnService service = new FashionVirtualTryOnService(tasks, renderer, assets, properties);
        ImageAssetMetadataStore metadata = mock(ImageAssetMetadataStore.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        service.setAssetMetadata(metadata);
        service.setCompletionPublisher(events);

        FashionTryOnTask task = task();
        FashionImageAsset personAsset = new FashionImageAsset(11L, "img_person", 1, "image/jpeg");
        FashionImageAsset garmentAsset = new FashionImageAsset(12L, "img_garment", 2, "image/png");
        FashionTryOnWork work = new FashionTryOnWork("managed:bot-a:user-a", task, personAsset, garmentAsset, "T_SHIRT");
        StoredImage person = image("img_person", 1);
        StoredImage garment = image("img_garment", 2);
        StoredImage output = image("img_tryon", 1);
        when(tasks.claim(eq(task.id()), any())).thenReturn(Optional.of(work));
        when(assets.find(work.externalUserId(), "img_person", 1)).thenReturn(Optional.of(person));
        when(assets.find(work.externalUserId(), "img_garment", 2)).thenReturn(Optional.of(garment));
        when(renderer.render(eq(work.externalUserId()), eq(person), eq(garment), eq("T_SHIRT"), eq(Duration.ofSeconds(150))))
                .thenReturn(VirtualTryOnService.TryOnResult.image(new byte[]{9, 8, 7}, "https://provider.example/tryon"));
        when(assets.saveGenerated(eq(work.externalUserId()), eq("fashion-tryon:" + task.id()),
                eq(new byte[]{9, 8, 7}), eq("https://provider.example/tryon"))).thenReturn(output);

        service.execute(task.id());

        verify(metadata).record(work.externalUserId(), output, "local");
        verify(tasks).succeed(eq(task.id()), eq("img_tryon"), eq(1), any());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(FashionTryOnCompletedEvent.class);
        FashionTryOnCompletedEvent completed = (FashionTryOnCompletedEvent) event.getValue();
        assertThat(completed.userId()).isEqualTo(work.externalUserId());
        assertThat(completed.wardrobeItemId()).isEqualTo(task.wardrobeItemId());
        assertThat(completed.imageBytes()).containsExactly(9, 8, 7);
    }

    private static FashionTryOnTask task() {
        Instant now = Instant.now();
        return new FashionTryOnTask("task-tryon-1", 7L, "bot-a", "template-a", 41L, 11L, 12L,
                FashionTryOnTaskStatus.PROCESSING, 1, null, "", now, null, now, now);
    }

    private static StoredImage image(String assetId, int version) {
        return new StoredImage(assetId, version, Path.of("C:/test/" + assetId + ".png"), "", null,
                Instant.now(), "image/png", "generated", "");
    }
}
