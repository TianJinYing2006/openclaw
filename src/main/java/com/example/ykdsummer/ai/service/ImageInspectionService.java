package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Uses the active Chat Completions provider for local image inspection. */
@Service
public class ImageInspectionService {
    public static final String TEMPORARY_UNAVAILABLE_REPLY = "图片视觉识别暂时不可用，请根据已保存描述或向用户确认细节。";
    private static final int MAX_VISION_EDGE = 1280;
    private static final Logger log = LoggerFactory.getLogger(ImageInspectionService.class);
    private final ChatCompletionsVisionGateway visionGateway;
    private final LocalImageAssetStore imageStore;

    public ImageInspectionService(ChatCompletionsVisionGateway visionGateway, LocalImageAssetStore imageStore) {
        this.visionGateway = visionGateway;
        this.imageStore = imageStore;
    }

    public String inspect(StoredImage image, String question) {
        try {
            String signedUrl = signedOssUrl(image);
            if (signedUrl != null) {
                return visionGateway.inspect(prompt(question), signedUrl);
            }
            byte[] bytes = imageStore.readBytes(image);
            return visionGateway.inspect(prompt(question), compactForVision(image.mediaType(), bytes));
        } catch (RuntimeException exception) {
            log.warn("Image inspection failed for asset {}: {}", image.assetId(), exception.getMessage());
            return TEMPORARY_UNAVAILABLE_REPLY;
        }
    }

    private String signedOssUrl(StoredImage image) {
        if (!(imageStore instanceof OssImageAssetStore)) return null;
        try {
            String value = imageStore.signedReadUrl(image);
            return value != null && (value.startsWith("https://") || value.startsWith("http://")) ? value : null;
        } catch (RuntimeException exception) {
            log.warn("Could not create signed OSS URL for image asset {}; falling back to inline vision input", image.assetId());
            return null;
        }
    }

    private static String prompt(String question) {
        return "请只根据附图回答。" + (question == null || question.isBlank()
                ? "请概括主体、人物/物体、颜色、风格、构图和可见文字，供后续图片修改使用。"
                : question.strip());
    }

    public static boolean isTemporaryUnavailableReply(String value) {
        return TEMPORARY_UNAVAILABLE_REPLY.equals(value == null ? "" : value.strip());
    }

    private static AiImage compactForVision(String mediaType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Image bytes are required");
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
            if (original == null || Math.max(original.getWidth(), original.getHeight()) <= MAX_VISION_EDGE) {
                return new AiImage(mediaType, bytes, AiImage.Detail.LOW);
            }
            double scale = (double) MAX_VISION_EDGE / Math.max(original.getWidth(), original.getHeight());
            int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
            BufferedImage compact = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = compact.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(original, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(compact, "jpg", output)) return new AiImage(mediaType, bytes, AiImage.Detail.LOW);
                return new AiImage("image/jpeg", output.toByteArray(), AiImage.Detail.LOW);
            }
        } catch (IOException | RuntimeException ignored) {
            // Unsupported formats (for example, some WebP uploads) keep the original bytes and still use low detail.
            return new AiImage(mediaType, bytes, AiImage.Detail.LOW);
        }
    }

    public static ImageInspectionService unavailable() {
        return new ImageInspectionService(null, new LocalImageAssetStore() {
            @Override public byte[] readBytes(StoredImage image) { throw new IllegalStateException("图片视觉识别未配置"); }
        });
    }
}
