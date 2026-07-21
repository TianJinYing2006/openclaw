package com.example.ykdsummer.bot.video;

import com.example.ykdsummer.bot.config.VideoProcessingProperties;
import io.github.morningwn.client.ILinkClient;
import io.github.morningwn.protocol.CDNMedia;
import io.github.morningwn.protocol.MessageItem;
import io.github.morningwn.protocol.ProtocolValues;
import io.github.morningwn.protocol.VideoItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ILinkVideoDownloaderTest {

    @Test
    void downloadsAndDecryptsOneVideoWithMediaKey() {
        VideoProcessingProperties properties = new VideoProcessingProperties();
        ILinkVideoDownloader downloader = new ILinkVideoDownloader(properties);
        ILinkClient client = mock(ILinkClient.class);
        CDNMedia media = new CDNMedia("query", "media-key", 1, null);
        byte[] videoBytes = {1, 2, 3, 4};
        when(client.downloadAndDecryptMedia(media, null)).thenReturn(videoBytes);
        downloader.attach(client);

        byte[] result = downloader.downloadVideo(List.of(messageVideo(
                new VideoItem(media, 4L, 1_000L, null, null, null, null, null))));

        assertThat(result).containsExactly(videoBytes);
    }

    @Test
    void rejectsOversizedMetadataBeforeDownloading() {
        VideoProcessingProperties properties = new VideoProcessingProperties();
        ILinkVideoDownloader downloader = new ILinkVideoDownloader(properties);
        CDNMedia media = new CDNMedia("query", "key", 1, null);
        long tooLarge = properties.getMaxVideoSize().toBytes() + 1;

        assertThatThrownBy(() -> downloader.downloadVideo(List.of(messageVideo(
                new VideoItem(media, tooLarge, null, null, null, null, null, null)))))
                .isInstanceOf(VideoProcessingException.class)
                .hasMessageContaining("20 MiB");
    }

    @Test
    void rejectsMoreThanOneVideo() {
        VideoProcessingProperties properties = new VideoProcessingProperties();
        ILinkVideoDownloader downloader = new ILinkVideoDownloader(properties);
        VideoItem video = new VideoItem(null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> downloader.downloadVideo(List.of(messageVideo(video), messageVideo(video))))
                .isInstanceOf(VideoProcessingException.class)
                .hasMessageContaining("一个视频");
    }

    private static MessageItem messageVideo(VideoItem video) {
        return new MessageItem(
                ProtocolValues.ITEM_TYPE_VIDEO, null, null, true, null, null,
                null, null, null, null, video
        );
    }
}
