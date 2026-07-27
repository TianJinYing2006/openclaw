package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Uses the active Chat Completions provider for local image inspection. */
@Service
public class ImageInspectionService {
    private static final Logger log = LoggerFactory.getLogger(ImageInspectionService.class);
    private final ChatCompletionsVisionGateway visionGateway;
    private final LocalImageAssetStore imageStore;

    public ImageInspectionService(ChatCompletionsVisionGateway visionGateway, LocalImageAssetStore imageStore) {
        this.visionGateway = visionGateway;
        this.imageStore = imageStore;
    }

    public String inspect(StoredImage image, String question) {
        try {
            byte[] bytes = imageStore.readBytes(image);
            String prompt = "请只根据附图回答。" + (question == null || question.isBlank()
                    ? "请概括主体、人物/物体、颜色、风格、构图和可见文字，供后续图片修改使用。"
                    : question.strip());
            return visionGateway.inspect(prompt, new AiImage(image.mediaType(), bytes));
        } catch (RuntimeException exception) {
            log.warn("Image inspection failed for asset {}: {}", image.assetId(), exception.getMessage());
            return "图片视觉识别暂时不可用，请根据已保存描述或向用户确认细节。";
        }
    }

    public static ImageInspectionService unavailable() {
        return new ImageInspectionService(null, new LocalImageAssetStore() {
            @Override public byte[] readBytes(StoredImage image) { throw new IllegalStateException("图片视觉识别未配置"); }
        });
    }
}
