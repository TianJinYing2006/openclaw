package com.example.ykdsummer.bot.video;

import com.example.ykdsummer.bot.config.VideoProcessingProperties;
import com.example.ykdsummer.bot.message.ILinkMessageType;
import io.github.morningwn.client.ILinkClient;
import io.github.morningwn.protocol.CDNMedia;
import io.github.morningwn.protocol.MessageItem;
import io.github.morningwn.protocol.VideoItem;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 从腾讯 CDN 下载并解密一条微信视频。
 *
 * <p>本类不负责抽帧。它的输出仍然是完整视频 byte[]，随后才交给
 * {@link VideoFrameExtractor}。与图片下载器分开可以让两种媒体拥有不同的大小限制。</p>
 */
@Component
public class ILinkVideoDownloader {

    private final VideoProcessingProperties properties;
    private volatile ILinkClient client;

    public ILinkVideoDownloader(VideoProcessingProperties properties) {
        this.properties = properties;
    }

    public void attach(ILinkClient client) {
        this.client = client;
    }

    public void detach(ILinkClient expectedClient) {
        if (this.client == expectedClient) {
            this.client = null;
        }
    }

    public byte[] downloadVideo(List<MessageItem> items) {
        List<VideoItem> videos = items.stream()
                .filter(item -> item != null && ILinkMessageType.from(item.type()) == ILinkMessageType.VIDEO)
                .map(MessageItem::videoItem)
                .filter(java.util.Objects::nonNull)
                .toList();

        if (videos.size() != 1) {
            throw new VideoProcessingException(videos.isEmpty()
                    ? "没有读取到视频内容，请重新发送"
                    : "一次只能解析一个视频");
        }

        VideoItem video = videos.getFirst();
        long maxBytes = properties.getMaxVideoSize().toBytes();
        if (video.videoSize() != null && video.videoSize() > maxBytes) {
            throw new VideoProcessingException("视频不能超过 " + properties.getMaxVideoSize().toMegabytes() + " MiB");
        }
        CDNMedia media = video.media();
        if (media == null) {
            throw new VideoProcessingException("视频读取失败，请重新发送");
        }
        ILinkClient currentClient = client;
        if (currentClient == null) {
            throw new VideoProcessingException("视频读取服务尚未连接，请稍后重试");
        }

        byte[] bytes;
        try {
            /*
             * 第二个参数传 null 时，SDK 使用 CDNMedia.aesKey() 下载并做 AES 解密。
             * 返回值已经是普通视频字节，不再是腾讯 CDN 密文。
             */
            bytes = currentClient.downloadAndDecryptMedia(media, null);
        } catch (RuntimeException exception) {
            throw new VideoProcessingException("视频读取失败，请重新发送", exception);
        }
        if (bytes == null || bytes.length == 0) {
            throw new VideoProcessingException("视频内容为空，请重新发送");
        }
        if (bytes.length > maxBytes) {
            throw new VideoProcessingException("视频不能超过 " + properties.getMaxVideoSize().toMegabytes() + " MiB");
        }

        verifyMd5WhenPresent(video.videoMd5(), bytes);
        return bytes;
    }

    private static void verifyMd5WhenPresent(String expected, byte[] bytes) {
        if (expected == null || !expected.matches("(?i)[0-9a-f]{32}")) {
            return;
        }
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
            if (!actual.equalsIgnoreCase(expected)) {
                throw new VideoProcessingException("视频校验失败，请重新发送");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide MD5", impossible);
        }
    }
}
