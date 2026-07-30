package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.config.OutfitRecommendationProperties;
import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.OutfitRenderStatus;
import com.example.ykdsummer.fashion.domain.OutfitRenderWork;
import com.example.ykdsummer.fashion.persistence.OutfitRecommendationRepository;
import com.example.ykdsummer.fashion.runtime.FashionOutfitRecommendationCompletedEvent;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class OutfitRecommendationRenderServiceTest {

    @Test
    void savesProviderImageAndPublishesOnlyAfterDurableCompletion() throws Exception {
        Fixture fixture = fixture();
        byte[] generated = solid(Color.GREEN, 300, 300);
        when(fixture.renderer.render(eq("wechat-user"), any(), any()))
                .thenReturn(OutfitRenderService.RenderResult.image(generated, "https://provider/outfit.png"));
        when(fixture.images.saveGenerated(eq("wechat-user"), eq("fashion-outfit:option-1"),
                eq(generated), eq("https://provider/outfit.png"))).thenReturn(fixture.output);

        fixture.service.execute("option-1");

        verify(fixture.repository).completeRender(eq("option-1"), eq("img_output"), eq(1),
                eq(OutfitRenderStatus.SUCCEEDED), any(Instant.class));
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(fixture.publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(FashionOutfitRecommendationCompletedEvent.class);
        assertThat(((FashionOutfitRecommendationCompletedEvent) event.getValue()).renderStatus())
                .isEqualTo(OutfitRenderStatus.SUCCEEDED);
    }

    @Test
    void providerFailureFallsBackToARealPixelBoard() throws Exception {
        Fixture fixture = fixture();
        when(fixture.renderer.render(eq("wechat-user"), any(), any()))
                .thenThrow(new IllegalStateException("provider timeout"));
        when(fixture.images.readBytes(fixture.top)).thenReturn(solid(Color.RED, 220, 180));
        when(fixture.images.readBytes(fixture.bottom)).thenReturn(solid(Color.BLUE, 180, 300));
        when(fixture.images.saveGenerated(eq("wechat-user"), eq("fashion-outfit:option-1"),
                any(byte[].class), isNull())).thenReturn(fixture.output);

        fixture.service.execute("option-1");

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.images).saveGenerated(eq("wechat-user"), eq("fashion-outfit:option-1"),
                bytes.capture(), isNull());
        BufferedImage board = ImageIO.read(new ByteArrayInputStream(bytes.getValue()));
        assertThat(board).isNotNull();
        assertThat(board.getWidth()).isEqualTo(900);
        assertThat(board.getHeight()).isEqualTo(1200);
        verify(fixture.repository).completeRender(eq("option-1"), eq("img_output"), eq(1),
                eq(OutfitRenderStatus.FALLBACK), any(Instant.class));
    }

    private static Fixture fixture() {
        OutfitRecommendationRepository repository = mock(OutfitRecommendationRepository.class);
        OutfitRenderService renderer = mock(OutfitRenderService.class);
        LocalImageAssetStore images = mock(LocalImageAssetStore.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        StoredImage top = stored("img_top", Path.of("top.png"));
        StoredImage bottom = stored("img_bottom", Path.of("bottom.png"));
        StoredImage output = stored("img_output", Path.of("output.png"));
        OutfitRenderWork work = new OutfitRenderWork("wechat-user", "run-1", "option-1", 1,
                "白色短袖 + 深蓝牛仔裤", List.of(
                new OutfitRenderWork.SourceItem(11L, "TOP", "白色短袖",
                        new FashionImageAsset(101L, "img_top", 1, "image/png")),
                new OutfitRenderWork.SourceItem(12L, "BOTTOM", "深蓝牛仔裤",
                        new FashionImageAsset(102L, "img_bottom", 1, "image/png"))));
        when(repository.claimRender(eq("option-1"), any(Instant.class))).thenReturn(Optional.of(work));
        when(images.find("wechat-user", "img_top", 1)).thenReturn(Optional.of(top));
        when(images.find("wechat-user", "img_bottom", 1)).thenReturn(Optional.of(bottom));
        OutfitRecommendationRenderService service = new OutfitRecommendationRenderService(
                repository, renderer, images, new OutfitRecommendationProperties());
        service.setCompletionPublisher(publisher);
        return new Fixture(repository, renderer, images, publisher, service, top, bottom, output);
    }

    private static StoredImage stored(String id, Path path) {
        return new StoredImage(id, 1, path, "", "", Instant.now(), "image/png", "test", "");
    }

    private static byte[] solid(Color color, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private record Fixture(
            OutfitRecommendationRepository repository,
            OutfitRenderService renderer,
            LocalImageAssetStore images,
            ApplicationEventPublisher publisher,
            OutfitRecommendationRenderService service,
            StoredImage top,
            StoredImage bottom,
            StoredImage output
    ) { }
}
