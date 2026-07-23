package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.openai.client.OpenAIClient;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.images.Image;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 调用 OpenAI Images 兼容接口，把图片描述变成图片字节。
 *
 * <p>这个接口与文字的 Responses/Chat Completions 完全独立：它调用
 * {@code /v1/images/generations}，收到 Base64 图片后再交给 iLink SDK 上传到腾讯 CDN。</p>
 */
@Service
public class AiImageGenerationService {
    public static final String DISABLED_REPLY = "图片生成功能暂未启用";
    public static final String AUTH_ERROR_REPLY = "图片服务认证失败，请联系管理员";
    public static final String UNAVAILABLE_REPLY = "图片生成暂时没有响应，请稍后重试";
    public static final String EMPTY_REPLY = "图片服务没有返回有效图片";

    private static final Logger log = LoggerFactory.getLogger(AiImageGenerationService.class);
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private static final HttpClient IMAGE_DOWNLOAD_CLIENT = HttpClient.newBuilder().build();

    private final OpenAIClient client;
    private final AiProperties properties;
    private final AsyncImageEditGateway imageEditGateway;

    public AiImageGenerationService(
            @Qualifier("imageOpenAIClient") OpenAIClient client,
            AiProperties properties
    ) {
        this(client, properties, (prompt, referenceImageUrl) -> AsyncImageEditGateway.EditResult.error("图片编辑服务未配置"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiImageGenerationService(
            @Qualifier("imageOpenAIClient") OpenAIClient client,
            AiProperties properties,
            AsyncImageEditGateway imageEditGateway
    ) {
        this.client = client;
        this.properties = properties;
        this.imageEditGateway = imageEditGateway;
    }

    /** 基于已保存原图创建新版本；参考图通过短时 OSS URL 进入异步媒体协议。 */
    public Result revise(String userId, String prompt, String referenceImageUrl) {
        if (!properties.isEnabled() || !properties.isImageEnabled()) {
            return Result.error(DISABLED_REPLY);
        }
        AsyncImageEditGateway.EditResult result = imageEditGateway.edit(prompt, referenceImageUrl);
        if (!result.hasImage()) {
            log.warn("AI image revision failed, user={}", anonymize(userId));
            return Result.error(result.errorMessage());
        }
        log.info("AI image revision completed, user={}, model={}, bytes={}", anonymize(userId),
                properties.getImageModel(), result.imageBytes().length);
        return Result.image(result.imageBytes(), result.remoteUrl());
    }

    /**
     * @param userId 仅用于脱敏日志，不会放进生图提示词
     * @param prompt 由用户或 Agent 组织好的画面描述
     */
    public Result generate(String userId, String prompt) {
        if (!properties.isEnabled() || !properties.isImageEnabled()) {
            return Result.error(DISABLED_REPLY);
        }
        try {
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
                    .withOptions(options -> options.timeout(properties.getImageTimeout()).maxRetries(0))
                    .generate(params);
            List<Image> images = response.data().orElse(List.of());
            if (images.isEmpty()) {
                return Result.error(EMPTY_REPLY);
            }
            byte[] bytes = imageBytes(images.getFirst());
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                return Result.error(EMPTY_REPLY);
            }
            log.info("AI image completed, user={}, model={}, bytes={}", anonymize(userId), properties.getImageModel(), bytes.length);
            return Result.image(bytes);
        } catch (UnauthorizedException | PermissionDeniedException exception) {
            log.warn("AI image authentication failed, user={}", anonymize(userId));
            return Result.error(AUTH_ERROR_REPLY);
        } catch (RuntimeException exception) {
            log.warn("AI image request failed, user={}, category={}, type={}",
                    anonymize(userId), failureCategory(exception), exception.getClass().getSimpleName());
            return Result.error(UNAVAILABLE_REPLY);
        }
    }

    /**
     * OpenAI Images 支持 Base64 与临时 URL 两种返回形式。部分兼容平台即使收到
     * {@code response_format=b64_json} 也只返回 URL，因此两种形式都要转成可发送给 iLink 的字节。
     */
    private byte[] imageBytes(Image image) {
        if (image == null) {
            return null;
        }
        if (image.b64Json().isPresent()) {
            try {
                return Base64.getDecoder().decode(image.b64Json().orElseThrow());
            } catch (IllegalArgumentException exception) {
                log.warn("Image provider returned invalid Base64 data");
            }
        }
        return image.url().map(this::downloadImage).orElse(null);
    }

    private byte[] downloadImage(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("unsupported image URL scheme");
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Accept", "image/*")
                    .timeout(properties.getImageTimeout())
                    .GET()
                    .build();
            HttpResponse<InputStream> response = IMAGE_DOWNLOAD_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("image URL returned HTTP " + response.statusCode());
            }
            try (InputStream stream = response.body()) {
                return readImageBytes(stream);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while downloading generated image", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("could not download generated image", exception);
        }
    }

    private static byte[] readImageBytes(InputStream stream) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > MAX_IMAGE_BYTES) {
                    throw new IllegalStateException("generated image exceeds maximum size");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String failureCategory(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String name = current.getClass().getSimpleName().toLowerCase();
            if (name.contains("timeout")) return "TIMEOUT";
            if (name.contains("ratelimit") || name.contains("too many")) return "RATE_LIMIT";
        }
        return "UPSTREAM_OR_NETWORK";
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    /**
     * 成功时 imageBytes 有值，失败时 errorMessage 有值。数组复制用于避免调用方意外改坏图片。
     */
    public record Result(byte[] imageBytes, String errorMessage, String remoteUrl) {
        public static Result image(byte[] bytes) { return image(bytes, null); }
        public static Result image(byte[] bytes, String remoteUrl) { return new Result(bytes.clone(), null, remoteUrl); }
        public static Result error(String message) { return new Result(null, message, null); }
        public boolean hasImage() { return imageBytes != null; }
        @Override public byte[] imageBytes() { return imageBytes == null ? null : imageBytes.clone(); }
    }
}
