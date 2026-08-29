package com.wechatbot.fashion.bot.service;

import io.github.morningwn.client.ILinkClient;
import io.github.morningwn.protocol.CDNMedia;
import io.github.morningwn.protocol.ImageItem;
import io.github.morningwn.protocol.MessageItem;
import io.github.morningwn.protocol.ProtocolValues;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ILinkMediaDownloaderTest {

    @Test
    void downloadsAndRecognizesPngWithoutWritingAFile() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};
        CDNMedia media = new CDNMedia("query", "key", 1, null);
        ImageItem image = new ImageItem(media, null, "aes", null, 9L, null, null, null, null);
        ILinkClient client = mock(ILinkClient.class);
        when(client.downloadAndDecryptMedia(media, "aes")).thenReturn(png);

        ILinkMediaDownloader downloader = new ILinkMediaDownloader();
        downloader.attach(client);
        var result = downloader.downloadImages(List.of(messageImage(image)));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().mediaType()).isEqualTo("image/png");
        assertThat(result.getFirst().bytes()).isEqualTo(png);
    }

    @Test
    void recognizesJpegAndWebpAndRejectsUnknownBytes() {
        assertThat(ILinkMediaDownloader.detectMediaType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))
                .isEqualTo("image/jpeg");

        byte[] webp = "RIFF1234WEBP".getBytes(StandardCharsets.US_ASCII);
        assertThat(ILinkMediaDownloader.detectMediaType(webp)).isEqualTo("image/webp");

        assertThatThrownBy(() -> ILinkMediaDownloader.detectMediaType(new byte[]{1, 2, 3}))
                .isInstanceOf(ILinkMediaDownloader.MediaProcessingException.class)
                .hasMessageContaining("JPEG");
    }

    @Test
    void rejectsMoreThanThreeImagesBeforeDownloading() {
        ILinkMediaDownloader downloader = new ILinkMediaDownloader();
        ImageItem image = new ImageItem(
                new CDNMedia("query", "key", 1, null), null, null, null,
                null, null, null, null, null
        );

        assertThatThrownBy(() -> downloader.downloadImages(List.of(
                messageImage(image), messageImage(image), messageImage(image), messageImage(image)
        )))
                .isInstanceOf(ILinkMediaDownloader.MediaProcessingException.class)
                .hasMessageContaining("3 张");
    }

    private static MessageItem messageImage(ImageItem image) {
        return new MessageItem(
                ProtocolValues.ITEM_TYPE_IMAGE, null, null, true, null, null,
                null, image, null, null, null
        );
    }
}
