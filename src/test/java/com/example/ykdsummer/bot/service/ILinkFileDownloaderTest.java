package com.example.ykdsummer.bot.service;

import io.github.morningwn.client.ILinkClient;
import io.github.morningwn.protocol.CDNMedia;
import io.github.morningwn.protocol.FileItem;
import io.github.morningwn.protocol.MessageItem;
import io.github.morningwn.protocol.ProtocolValues;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ILinkFileDownloaderTest {

    @Test
    void downloadsDecryptsAndMapsFileWithoutWritingToDisk() throws Exception {
        byte[] bytes = "file-marker".getBytes(StandardCharsets.UTF_8);
        CDNMedia media = new CDNMedia("query", "key", 1, null);
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        FileItem file = new FileItem(media, "folder\\sample.xml", md5, Integer.toString(bytes.length));
        ILinkClient client = mock(ILinkClient.class);
        when(client.downloadAndDecryptMedia(media, null)).thenReturn(bytes);

        ILinkFileDownloader downloader = new ILinkFileDownloader();
        downloader.attach(client);
        var result = downloader.downloadFiles(List.of(messageFile(file)));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().fileName()).isEqualTo("sample.xml");
        assertThat(result.getFirst().mediaType()).isEqualTo("text/xml");
        assertThat(result.getFirst().bytes()).containsExactly(bytes);
    }

    @Test
    void rejectsUnsupportedTypeBeforeDownloading() {
        ILinkFileDownloader downloader = new ILinkFileDownloader();
        downloader.attach(mock(ILinkClient.class));
        FileItem file = new FileItem(new CDNMedia("query", "key", 1, null), "payload.exe", null, "10");

        assertThatThrownBy(() -> downloader.downloadFiles(List.of(messageFile(file))))
                .isInstanceOf(ILinkFileDownloader.FileProcessingException.class)
                .hasMessageContaining("暂不支持");
    }

    @Test
    void rejectsMoreThanThreeFiles() {
        ILinkFileDownloader downloader = new ILinkFileDownloader();
        FileItem file = new FileItem(null, "a.txt", null, "1");

        assertThatThrownBy(() -> downloader.downloadFiles(List.of(
                messageFile(file), messageFile(file), messageFile(file), messageFile(file))))
                .isInstanceOf(ILinkFileDownloader.FileProcessingException.class)
                .hasMessageContaining("3 个");
    }

    private static MessageItem messageFile(FileItem file) {
        return new MessageItem(
                ProtocolValues.ITEM_TYPE_FILE, null, null, true, null, null,
                null, null, null, file, null
        );
    }
}
