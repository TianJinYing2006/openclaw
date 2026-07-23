package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * 调用阿里云百炼的图片生成接口，把"生图：描述"变成图片字节。
 *
 * <p>百炼使用异步 API：先提交任务获取 task_id，再轮询获取结果。</p>
 */
@Service
public class AiImageGenerationService {
    public static final String DISABLED_REPLY = "图片生成功能暂未启用";
    public static final String AUTH_ERROR_REPLY = "图片服务认证失败，请联系管理员";
    public static final String UNAVAILABLE_REPLY = "图片生成暂时没有响应，请稍后重试";
    public static final String EMPTY_REPLY = "图片服务没有返回有效图片";
    public static final String BALANCE_REPLY = "图片服务余额不足，请联系管理员充值";

    private static final Logger log = LoggerFactory.getLogger(AiImageGenerationService.class);
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private static final String DASHSCOPE_SUBMIT_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
    private static final String DASHSCOPE_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/%s";

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${DASHSCOPE_API_KEY:${app.tts.api-key:not-configured}}")
    private String dashscopeApiKey;

    public AiImageGenerationService(AiProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
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
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank() || "not-configured".equals(dashscopeApiKey)) {
            return Result.error(AUTH_ERROR_REPLY);
        }

        try {
            // 1. 提交图片生成任务
            String taskId = submitTask(userId, prompt);
            if (taskId == null) {
                return Result.error(UNAVAILABLE_REPLY);
            }

            // 2. 轮询等待任务完成
            byte[] bytes = pollTaskResult(userId, taskId);
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                return Result.error(EMPTY_REPLY);
            }

            log.info("AI image completed, user={}, bytes={}", anonymize(userId), bytes.length);
            return Result.image(bytes);
        } catch (IOException | InterruptedException exception) {
            log.warn("AI image request failed, user={}, type={}, message={}",
                    anonymize(userId), exception.getClass().getSimpleName(), exception.getMessage());
            return Result.error(UNAVAILABLE_REPLY);
        } catch (Exception exception) {
            log.warn("AI image request failed, user={}, type={}, message={}",
                    anonymize(userId), exception.getClass().getSimpleName(), exception.getMessage());
            return Result.error(UNAVAILABLE_REPLY);
        }
    }

    private String submitTask(String userId, String prompt) throws IOException, InterruptedException {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("prompt", prompt);

        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("n", 1);
        // 百炼 API 使用 * 分隔尺寸，如 1024*1024
        String size = properties.getImageSize().replace("x", "*");
        parameters.put("size", size);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", "wanx2.1-t2i-turbo");
        root.set("input", input);
        root.set("parameters", parameters);

        HttpRequest request = HttpRequest.newBuilder(URI.create(DASHSCOPE_SUBMIT_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + dashscopeApiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(root)))
                .build();

        log.info("AI image submit task, user={}, prompt length={}", anonymize(userId), prompt.length());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("AI image submit HTTP error: status={}, body={}", response.statusCode(), response.body());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new RuntimeException("Authentication failed");
            }
            if (response.statusCode() == 402) {
                throw new RuntimeException("Insufficient balance");
            }
            return null;
        }

        JsonNode responseRoot = objectMapper.readTree(response.body());
        JsonNode output = responseRoot.path("output");
        String taskId = output.path("task_id").asText(null);
        if (taskId == null || taskId.isBlank()) {
            log.warn("AI image submit response missing task_id: {}", response.body());
            return null;
        }
        log.info("AI image task submitted, taskId={}", taskId);
        return taskId;
    }

    private byte[] pollTaskResult(String userId, String taskId) throws IOException, InterruptedException {
        String url = String.format(DASHSCOPE_TASK_URL, taskId);
        int maxAttempts = 60; // 最多等待 5 分钟

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Thread.sleep(5000); // 每 5 秒轮询一次

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + dashscopeApiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("AI image poll HTTP error: status={}, body={}", response.statusCode(), response.body());
                continue;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode output = root.path("output");
            String status = output.path("task_status").asText("");

            if ("SUCCEEDED".equals(status)) {
                JsonNode results = output.path("results");
                if (results.isArray() && !results.isEmpty()) {
                    JsonNode first = results.get(0);
                    String imageUrl = first.path("url").asText("");
                    if (!imageUrl.isBlank()) {
                        return downloadImage(imageUrl);
                    }
                    String b64 = first.path("b64_image").asText("");
                    if (!b64.isBlank()) {
                        return Base64.getDecoder().decode(b64);
                    }
                }
                log.warn("AI image task succeeded but no image found: {}", response.body());
                return null;
            } else if ("FAILED".equals(status)) {
                String message = output.path("message").asText("unknown error");
                log.warn("AI image task failed: {}", message);
                return null;
            }
            // 其他状态(PENDING, RUNNING)继续轮询
        }

        log.warn("AI image task timed out after {} attempts", maxAttempts);
        return null;
    }

    private byte[] downloadImage(String imageUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("AI image download failed: status={}", response.statusCode());
            return null;
        }
        return response.body();
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    /**
     * 成功时 imageBytes 有值，失败时 errorMessage 有值。
     */
    public record Result(byte[] imageBytes, String errorMessage) {
        public static Result image(byte[] bytes) { return new Result(bytes.clone(), null); }
        public static Result error(String message) { return new Result(null, message); }
        public boolean hasImage() { return imageBytes != null; }
        @Override public byte[] imageBytes() { return imageBytes == null ? null : imageBytes.clone(); }
    }
}
