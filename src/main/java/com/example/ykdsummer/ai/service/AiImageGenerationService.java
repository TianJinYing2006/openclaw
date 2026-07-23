package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.openai.client.OpenAIClient;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.images.Image;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * 调用 OpenAI 兼容的 {@code /v1/images/generations} 接口，把“生图：描述”变成图片字节。
 *
 * <p>它和 {@link OpenAiResponsesGateway} 是两个独立 API 分支：Responses 负责文字回答和
 * 看图，Images API 负责从文字生成新图。返回结果不是网页链接，而是 Base64 编码的 PNG，
 * 本类解码后交给 iLink SDK 上传到腾讯 CDN。</p>
 */
@Service
public class AiImageGenerationService {
    public static final String DISABLED_REPLY = "图片生成功能暂未启用";
    public static final String AUTH_ERROR_REPLY = "图片服务认证失败，请联系管理员";
    public static final String UNAVAILABLE_REPLY = "图片生成暂时没有响应，请稍后重试";
    public static final String EMPTY_REPLY = "图片服务没有返回有效图片";

    private static final Logger log = LoggerFactory.getLogger(AiImageGenerationService.class);
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private final OpenAIClient client;
    private final AiProperties properties;

    public AiImageGenerationService(
            @Qualifier("imageOpenAIClient") OpenAIClient client,
            AiProperties properties
    ) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * @param userId 仅用于脱敏日志，不会放进生图提示词
     * @param prompt 已去掉“生图：”前缀后的画面描述
     */
    public Result generate(String userId, String prompt) {
        if (!properties.isEnabled() || !properties.isImageEnabled()) {
            return Result.error(DISABLED_REPLY);
        }
        try {
            AiModelCallLogger.imageRequest(
                    log,
                    anonymize(userId),
                    properties.getImageModel(),
                    properties.getImageSize(),
                    properties.getImageQuality(),
                    prompt
            );
            // 这里构造 Images API 请求：一次生成 1 张 1024x1024、中等质量的 PNG。
            ImageGenerateParams params = ImageGenerateParams.builder()
                    .model(properties.getImageModel())
                    .prompt(prompt)
                    .n(1)
                    .size(properties.getImageSize())
                    .quality(ImageGenerateParams.Quality.of(properties.getImageQuality()))
                    .outputFormat(ImageGenerateParams.OutputFormat.PNG)
                    .responseFormat(ImageGenerateParams.ResponseFormat.B64_JSON)
                    .build();
            ImagesResponse response = client.images()
                    // 当前默认最多等待 15 分钟且不重试，避免一次重试让实际等待时间翻倍。
                    .withOptions(options -> options.timeout(properties.getImageTimeout()).maxRetries(0))
                    .generate(params);
            List<Image> images = response.data().orElse(List.of());
            if (images.isEmpty() || images.getFirst().b64Json().isEmpty()) {
                return Result.error(EMPTY_REPLY);
            }
            // b64_json 是传输格式；解码后才是 ILinkBot.sendImage 需要的原始 PNG 字节。
            byte[] bytes = Base64.getDecoder().decode(images.getFirst().b64Json().orElseThrow());
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                return Result.error(EMPTY_REPLY);
            }
            AiModelCallLogger.imageResponse(log, anonymize(userId), properties.getImageModel(), bytes);
            return Result.image(bytes);
        } catch (UnauthorizedException | PermissionDeniedException exception) {
            log.warn("生图请求失败，用户={}，异常类型={}", anonymize(userId), exception.getClass().getSimpleName(), exception);
            return Result.error(AUTH_ERROR_REPLY);
        } catch (RuntimeException exception) {
            log.warn("生图请求失败，用户={}，异常类型={}", anonymize(userId), exception.getClass().getSimpleName(), exception);
            return Result.error(UNAVAILABLE_REPLY);
        }
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    /**
     * 成功时 imageBytes 有值，失败时 errorMessage 有值。数组复制用于避免调用方意外改坏图片。
     */
    public record Result(byte[] imageBytes, String errorMessage) {
        public static Result image(byte[] bytes) { return new Result(bytes.clone(), null); }
        public static Result error(String message) { return new Result(null, message); }
        public boolean hasImage() { return imageBytes != null; }
        @Override public byte[] imageBytes() { return imageBytes == null ? null : imageBytes.clone(); }
    }
}
