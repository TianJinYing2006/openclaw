package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import java.util.List;
import org.springframework.stereotype.Service;

/** 使用既有 Responses 多模态通道读取本地图片，供 Chat Completions Agent 的图片工具使用。 */
@Service
public class ImageInspectionService {
    private final ResponsesGateway responsesGateway;
    private final LocalImageAssetStore imageStore;

    public ImageInspectionService(ResponsesGateway responsesGateway, LocalImageAssetStore imageStore) {
        this.responsesGateway = responsesGateway;
        this.imageStore = imageStore;
    }

    public String inspect(StoredImage image, String question) {
        try {
            byte[] bytes = imageStore.readBytes(image);
            String prompt = "请只根据附图回答。" + (question == null || question.isBlank()
                    ? "请概括主体、人物/物体、颜色、风格、构图和可见文字，供后续图片修改使用。"
                    : question.strip());
            return responsesGateway.generate(List.<ConversationMessage>of(), prompt,
                    List.of(new AiImage(image.mediaType(), bytes)), List.of()).text();
        } catch (RuntimeException exception) {
            return "图片视觉识别暂时不可用，请根据已保存描述或向用户确认细节。";
        }
    }

    public static ImageInspectionService unavailable() {
        return new ImageInspectionService((history, prompt, images, files) -> {
            throw new IllegalStateException("图片视觉识别未配置");
        }, new LocalImageAssetStore() {
            @Override public byte[] readBytes(StoredImage image) { throw new IllegalStateException("图片视觉识别未配置"); }
        });
    }
}
