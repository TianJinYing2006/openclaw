package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * 调用阿里云百炼原生 {@code /api/v1/services/aigc/multimodal-generation/generation}
 * 接口，把“生图：描述”变成图片字节。
 *
 * <p>百炼的图片生成不走 OpenAI 兼容端点，而是使用与 TTS 类似的 DashScope 原生 API 格式。
 * 本类借鉴 {@link com.example.ykdsummer.bot.audio.AliyunDashScopeTtsService} 的实现模式，
 * 通过 {@link java.net.http.HttpClient} 直接发送 JSON 请求。</p>
 */
@Service
public class AiImageGenerationService {
    public static final String DISABLED_REPLY = "图片生成功能暂未启用";
    public static final String AUTH_ERROR_REPLY = "图片服务认证失败，请联系管理员";
    public static final String UNAVAILABLE_REPLY = "图片生成暂时没有响应，请稍后重试";
    public static final String EMPTY_REPLY = "图片服务没有返回有效图片";

    private static final Logger log = LoggerFactory.getLogger(AiImageGenerationService.class);
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private static final String DASHSCOPE_IMAGE_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    private final AiProperties properties;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AiImageGenerationService(
            AiProperties properties,
            @Value("${DASHSCOPE_API_KEY:}") String apiKey
    ) {
        this.properties = properties;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * @param userId 仅用于脱敏日志，不会放进生图提示词
     * @param prompt 已去掉"生图："前缀后的画面描述
     */
    public Result generate(String userId, String prompt) {
        if (!properties.isEnabled() || !properties.isImageEnabled()) {
            return Result.error(DISABLED_REPLY);
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("AI image generation disabled: DASHSCOPE_API_KEY not configured");
            return Result.error(DISABLED_REPLY);
        }

        try {
            // 构造百炼原生多模态生成请求体
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", properties.getImageModel());

            // input.messages[0].role=user, content[0].text=prompt
            ObjectNode input = objectMapper.createObjectNode();
            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "user");
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode textContent = objectMapper.createObjectNode();
            textContent.put("text", prompt);
            content.add(textContent);
            message.set("content", content);
            messages.add(message);
            input.set("messages", messages);
            body.set("input", input);

            // parameters: size 分隔符用 *（百炼格式）
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("size", properties.getImageSize().replace("x", "*"));
            body.set("parameters", parameters);

            HttpRequest request = HttpRequest.newBuilder(URI.create(DASHSCOPE_IMAGE_URL))
                    .timeout(safeTimeout(properties.getImageTimeout()))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    log.warn("AI image authentication failed, user={}", anonymize(userId));
                    return Result.error(AUTH_ERROR_REPLY);
                }
                log.warn("AI image request failed, user={}, status={}",
                        anonymize(userId), response.statusCode());
                return Result.error(UNAVAILABLE_REPLY);
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode output = root.path("output");

            // 百炼实际返回格式: output.choices[0].message.content[0].image
            JsonNode choices = output.path("choices");
            if (!choices.isEmpty()) {
                JsonNode contentNode = choices.get(0).path("message").path("content");
                if (contentNode.isArray() && !contentNode.isEmpty()) {
                    String imageUrl = contentNode.get(0).path("image").asText("");
                    if (!imageUrl.isBlank()) {
                        HttpRequest download = HttpRequest.newBuilder(URI.create(imageUrl))
                                .timeout(Duration.ofSeconds(30))
                                .GET()
                                .build();
                        HttpResponse<byte[]> downloadResponse = httpClient.send(download, HttpResponse.BodyHandlers.ofByteArray());
                        if (downloadResponse.statusCode() == 200 && downloadResponse.body().length > 0) {
                            byte[] bytes = downloadResponse.body();
                            if (bytes.length <= MAX_IMAGE_BYTES) {
                                log.info("AI image completed, user={}, model={}, bytes={}",
                                        anonymize(userId), properties.getImageModel(), bytes.length);
                                return Result.image(bytes);
                            }
                        }
                    }
                }
            }

            log.warn("No image found in DashScope response, raw={}", truncate(response.body(), 300));
            return Result.error(EMPTY_REPLY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("AI image request interrupted, user={}", anonymize(userId));
            return Result.error(UNAVAILABLE_REPLY);
        } catch (Exception e) {
            log.warn("AI image request failed, user={}, type={}", anonymize(userId), e.getClass().getSimpleName());
            return Result.error(UNAVAILABLE_REPLY);
        }
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static Duration safeTimeout(Duration timeout) {
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofMinutes(15)
                : timeout;
    }

    /**
     * 成功时 imageBytes 有值，失败时 errorMessage 有值。
     */
    public record Result(byte[] imageBytes, String errorMessage) {
        public static Result image(byte[] bytes) {
            return new Result(bytes.clone(), null);
        }

        public static Result error(String message) {
            return new Result(null, message);
        }

        public boolean hasImage() {
            return imageBytes != null;
        }

        @Override
        public byte[] imageBytes() {
            return imageBytes == null ? null : imageBytes.clone();
        }
    }
}
