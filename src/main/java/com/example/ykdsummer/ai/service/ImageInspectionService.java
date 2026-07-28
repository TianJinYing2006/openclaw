package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.provider.dashscope.DashScopeVisionService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 图片视觉识别服务。
 *
 * <p>将已保存的图片资产通过 {@link DashScopeVisionService}（百炼 Chat Completions API）
 * 送往多模态模型进行内容理解，返回图片描述或对具体问题的回答。</p>
 */
@Service
public class ImageInspectionService {

    private static final Logger log = LoggerFactory.getLogger(ImageInspectionService.class);

    private final LocalImageAssetStore imageStore;
    private final DashScopeVisionService visionService;

    public ImageInspectionService(LocalImageAssetStore imageStore, DashScopeVisionService visionService) {
        this.imageStore = imageStore;
        this.visionService = visionService;
    }

    /**
     * 识别指定图片的内容。
     *
     * @param image    已保存的图片资产
     * @param question 对图片的具体问题；为空时做通用描述
     * @return 模型返回的文本描述
     */
    public String inspect(StoredImage image, String question) {
        try {
            byte[] bytes = imageStore.readBytes(image);
            log.debug("Inspecting image: assetId={}, questionLength={}, bytes={}",
                    image.assetId(), question != null ? question.length() : 0, bytes.length);
            return visionService.inspect(bytes, image.mediaType(), question);
        } catch (Exception e) {
            log.warn("Image inspection failed, assetId={}, type={}, message={}",
                    image != null ? image.assetId() : "null",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            return "图片识别暂时不可用，请根据已保存描述或向用户确认细节。";
        }
    }

    /** 返回一个降级实例，用于测试等不需要真实视觉识别的场景。 */
    public static ImageInspectionService unavailable() {
        return new ImageInspectionService(null, null);
    }
}
