package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.bot.message.ILinkMessageType;
import io.github.morningwn.client.ILinkClient;
import io.github.morningwn.protocol.CDNMedia;
import io.github.morningwn.protocol.ImageItem;
import io.github.morningwn.protocol.MessageItem;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 处理“微信用户发图片给机器人”这一方向：从腾讯 CDN 下载密文并解密为原始图片。
 *
 * <p>iLink 的消息正文不会直接塞入整张图片，只带有 {@link CDNMedia} 下载信息和 AES key。
 * 本类使用与 {@link io.github.morningwn.client.ILinkBot} 相同的底层客户端调用 SDK 的
 * {@code downloadAndDecryptMedia(...)}。SDK 内部请求腾讯 CDN，并按协议完成
 * AES-128-ECB/PKCS5 兼容解密；本项目再检查大小和真实图片格式。</p>
 *
 * <p>这里是“腾讯 CDN -&gt; Java -&gt; 模型”的入站方向。模型生成图片后发回微信属于相反的
 * 出站方向，由 SDK 的 {@code sendImage(...)} 负责加密上传。图片只保存在当前请求的
 * byte[] 中，不写磁盘，也不把二进制加入聊天历史。</p>
 */
@Service
public class ILinkMediaDownloader {

    static final int MAX_IMAGES = 3;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 10L * 1024 * 1024;

    /** ILinkBotService 启动时挂接的共享 SDK 客户端；volatile 保证工作线程能看到最新引用。 */
    private volatile ILinkClient client;

    /** iLink 启动成功后由 ILinkBotService 调用，使本类可以复用已配置好的客户端。 */
    public void attach(ILinkClient client) {
        this.client = client;
    }

    /** 应用关闭或启动失败时解除引用；只解除预期的那个客户端，避免误清新连接。 */
    public void detach(ILinkClient expectedClient) {
        if (client == expectedClient) {
            client = null;
        }
    }

    /**
     * 从一条微信消息的全部 item 中找出图片并下载。
     *
     * @param items SDK 已解析的一条消息内容列表，可能同时有文字和图片
     * @return 已解密且通过格式检查的模型图片，最多 3 张
     */
    public List<AiImage> downloadImages(List<MessageItem> items) {
        return downloadImages(client, items);
    }

    /** Uses an explicitly owned client for a managed bot instance. */
    public List<AiImage> downloadImages(ILinkClient currentClient, List<MessageItem> items) {
        List<ImageItem> imageItems = items.stream()
                .filter(item -> item != null && ILinkMessageType.from(item.type()) == ILinkMessageType.IMAGE)
                .map(MessageItem::imageItem)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (imageItems.size() > MAX_IMAGES) {
            throw new MediaProcessingException("一次最多发送 3 张图片");
        }

        if (currentClient == null) {
            throw new MediaProcessingException("图片读取失败，请重新发送");
        }

        long totalBytes = 0;
        List<AiImage> result = new ArrayList<>(imageItems.size());
        for (ImageItem image : imageItems) {
            // 先用消息附带的尺寸元数据快速拒绝超大图片，减少无意义的 CDN 下载。
            rejectOversizedMetadata(image);
            // 优先取原图 media；没有原图地址时才退回缩略图 thumbMedia。
            CDNMedia media = image.media() != null ? image.media() : image.thumbMedia();
            if (media == null) {
                throw new MediaProcessingException("图片读取失败，请重新发送");
            }

            byte[] bytes;
            try {
                /*
                 * 本地只调用这一行 SDK。它内部会读取 media 的 CDN 地址/参数、下载加密字节，
                 * 再使用 image.aeskey（必要时结合 media 自带 key）解密后返回原始图片。
                 */
                bytes = currentClient.downloadAndDecryptMedia(media, image.aeskey());
            } catch (RuntimeException exception) {
                throw new MediaProcessingException("图片读取失败，请重新发送", exception);
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new MediaProcessingException("单张图片不能超过 5 MiB");
            }
            totalBytes += bytes.length;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new MediaProcessingException("图片总大小不能超过 10 MiB");
            }
            // 不信任文件扩展名，直接检查文件头，确认确实是 JPEG、PNG 或 WebP。
            result.add(new AiImage(detectMediaType(bytes), bytes));
        }
        return List.copyOf(result);
    }

    private static void rejectOversizedMetadata(ImageItem image) {
        if (isTooLarge(image.hdSize()) || isTooLarge(image.midSize()) || isTooLarge(image.thumbSize())) {
            throw new MediaProcessingException("单张图片不能超过 5 MiB");
        }
    }

    private static boolean isTooLarge(Long size) {
        return size != null && size > MAX_IMAGE_BYTES;
    }

    /** 根据图片开头的固定字节（magic bytes）判断真实格式。 */
    static String detectMediaType(byte[] bytes) {
        if (bytes.length >= 3
                && unsigned(bytes[0]) == 0xFF
                && unsigned(bytes[1]) == 0xD8
                && unsigned(bytes[2]) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && unsigned(bytes[0]) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G'
                && unsigned(bytes[4]) == 0x0D
                && unsigned(bytes[5]) == 0x0A
                && unsigned(bytes[6]) == 0x1A
                && unsigned(bytes[7]) == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 12
                && "RIFF".equals(new String(bytes, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII))) {
            return "image/webp";
        }
        throw new MediaProcessingException("目前只支持 JPEG、PNG 和 WebP 图片");
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    /**
     * 媒体失败的本地异常。userMessage 是可以安全回复给微信用户的简短说明，cause 只写日志。
     */
    public static class MediaProcessingException extends RuntimeException {

        private final String userMessage;

        MediaProcessingException(String userMessage) {
            super(userMessage);
            this.userMessage = userMessage;
        }

        MediaProcessingException(String userMessage, Throwable cause) {
            super(userMessage, cause);
            this.userMessage = userMessage;
        }

        public String userMessage() {
            return userMessage;
        }
    }
}
