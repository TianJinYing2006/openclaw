package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.model.AiImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ImageInspectionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void scalesLargeUploadsAndRequestsLowVisionDetail() throws Exception {
        ChatCompletionsVisionGateway gateway = mock(ChatCompletionsVisionGateway.class);
        when(gateway.inspect(anyString(), any(AiImage.class))).thenReturn("ok");
        LocalImageAssetStore store = new LocalImageAssetStore(tempDir);
        ImageInspectionService service = new ImageInspectionService(gateway, store);
        LocalImageAssetStore.StoredImage image = store.saveIncoming("user", "large", largeJpeg(), "image/jpeg");

        assertThat(service.inspect(image, "describe clothing")).isEqualTo("ok");

        ArgumentCaptor<AiImage> captured = ArgumentCaptor.forClass(AiImage.class);
        verify(gateway).inspect(anyString(), captured.capture());
        AiImage sent = captured.getValue();
        BufferedImage compact = ImageIO.read(new ByteArrayInputStream(sent.bytes()));
        assertThat(sent.detail()).isEqualTo(AiImage.Detail.LOW);
        assertThat(sent.mediaType()).isEqualTo("image/jpeg");
        assertThat(Math.max(compact.getWidth(), compact.getHeight())).isLessThanOrEqualTo(1280);
    }

    @Test
    void sendsOssAssetsByShortLivedUrlInsteadOfReadingAndInliningThem() {
        ChatCompletionsVisionGateway gateway = mock(ChatCompletionsVisionGateway.class);
        OssImageAssetStore store = mock(OssImageAssetStore.class);
        LocalImageAssetStore.StoredImage image = new LocalImageAssetStore.StoredImage("img_123456789012", 1,
                Path.of("private/object.jpg"), "", null, java.time.Instant.now(), "image/jpeg", "uploaded", "");
        String signedUrl = "https://oss.example.test/private/object.jpg?signature=temporary";
        when(store.signedReadUrl(image)).thenReturn(signedUrl);
        when(gateway.inspect(anyString(), eq(signedUrl))).thenReturn("ok");

        assertThat(new ImageInspectionService(gateway, store).inspect(image, "describe clothing")).isEqualTo("ok");

        verify(gateway).inspect(anyString(), eq(signedUrl));
        verify(store, never()).readBytes(image);
    }

    private static byte[] largeJpeg() throws Exception {
        BufferedImage image = new BufferedImage(2400, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.LIGHT_GRAY);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.DARK_GRAY);
            graphics.fillRect(700, 200, 900, 1200);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, "jpg", output)).isTrue();
            return output.toByteArray();
        }
    }
}
