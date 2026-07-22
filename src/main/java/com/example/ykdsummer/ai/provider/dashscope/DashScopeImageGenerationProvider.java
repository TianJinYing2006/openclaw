package com.example.ykdsummer.ai.provider.dashscope;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.provider.ImageGenerationProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 阿里云百炼图片生成实现。
 *
 * <p>支持两类模型端点：
 * <ul>
 *   <li>{@code wan2.*} 系列（如 wan2.7-image-pro）→
 *       {@code /api/v1/services/aigc/multimodal-generation/generation}，messages 格式，同步</li>
 *   <li>{@code wanx2.*} 系列（如 wanx2.1-t2i-turbo）→
 *       {@code /api/v1/services/aigc/text2image/image-synthesis}，prompt 格式，异步轮询</li>
 * </ul>
 */
@Component
public class DashScopeImageGenerationProvider implements ImageGenerationProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeImageGenerationProvider.class);

    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;

    /** wan2 系列（新版 multimodal-generation 端点，同步）。 */
    private static final String MULTIMODAL_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    /** wanx 系列（旧版 text2image 端点，异步）。 */
    private static final String TEXT2IMAGE_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";

    /** 异步任务查询端点。 */
    private static final String TASK_QUERY_BASE =
            "https://dashscope.aliyuncs.com/api/v1/tasks/";

    /** 轮询间隔（毫秒）。 */
    private static final long POLL_INTERVAL_MS = 2000L;

    private final AiProperties aiProperties;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DashScopeImageGenerationProvider(
            AiProperties aiProperties,
            @Value("${DASHSCOPE_API_KEY:}") String apiKey
    ) {
        this.aiProperties = aiProperties;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public Result generate(String prompt, String size, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DashScope image generation disabled: DASHSCOPE_API_KEY not configured");
            return Result.error("图片服务未配置 API Key");
        }
        if (prompt == null || prompt.isBlank()) {
            return Result.error("图片描述不能为空");
        }

        String effectiveModel = (model != null && !model.isBlank()) ? model : aiProperties.getImageModel();
        String effectiveSize = (size != null && !size.isBlank()) ? size : aiProperties.getImageSize();

        try {
            if (effectiveModel.startsWith("wanx")) {
                return generateText2Image(effectiveModel, prompt, effectiveSize);
            } else {
                return generateMultimodal(effectiveModel, prompt, effectiveSize);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("图片生成请求被中断");
        } catch (Exception e) {
            log.warn("DashScope image request failed, type={}", e.getClass().getSimpleName(), e);
            return Result.error("图片生成失败");
        }
    }

    @Override
    public String providerName() {
        return "dashscope";
    }

    // ========== wan2 系列：multimodal-generation 端点（同步） ==========

    private Result generateMultimodal(String model, String prompt, String size) throws Exception {
        ObjectNode body = buildMultimodalBody(model, prompt, size);
        String bodyStr = objectMapper.writeValueAsString(body);
        log.info("DashScope multimodal request: model={}, size={}, body={}", model, size, bodyStr);

        HttpRequest request = HttpRequest.newBuilder(URI.create(MULTIMODAL_API_URL))
                .timeout(safeTimeout(aiProperties.getImageTimeout()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return handleHttpError(model, response);
        }
        return parseMultimodalResponse(response.body());
    }

    private ObjectNode buildMultimodalBody(String model, String prompt, String size) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);

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

        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("size", normalizeWan2Size(size));
        parameters.put("n", 1);
        parameters.put("watermark", false);
        body.set("parameters", parameters);

        return body;
    }

    /** 将配置尺寸（"1024x1024" 或 "2K"）转为 wan2.x 接受的规格值。 */
    private static String normalizeWan2Size(String size) {
        if (size == null) return "2K";
        String upper = size.toUpperCase();
        if (upper.equals("1K") || upper.equals("2K") || upper.equals("4K")) {
            return upper;
        }
        return "2K";
    }

    private Result parseMultimodalResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode output = root.path("output");
        JsonNode choices = output.path("choices");

        if (!choices.isEmpty()) {
            JsonNode contentNode = choices.get(0).path("message").path("content");
            if (contentNode.isArray() && !contentNode.isEmpty()) {
                String imageUrl = contentNode.get(0).path("image").asText("");
                if (!imageUrl.isBlank()) {
                    return downloadImage(imageUrl);
                }
            }
        }

        log.warn("No image found in multimodal response: {}",
                responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody);
        return Result.error("图片服务没有返回有效图片");
    }

    // ========== wanx 系列：text2image 端点（异步轮询） ==========

    private Result generateText2Image(String model, String prompt, String size) throws Exception {
        ObjectNode body = buildText2ImageBody(model, prompt, size);
        String bodyStr = objectMapper.writeValueAsString(body);
        log.info("DashScope text2image request: model={}, size={}, body={}", model, size, bodyStr);

        // 1) 提交异步任务
        HttpRequest submitRequest = HttpRequest.newBuilder(URI.create(TEXT2IMAGE_API_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());

        if (submitResponse.statusCode() != 200) {
            return handleHttpError(model, submitResponse);
        }

        JsonNode submitRoot = objectMapper.readTree(submitResponse.body());
        String taskId = submitRoot.path("output").path("task_id").asText("");
        if (taskId.isBlank()) {
            log.warn("No task_id in text2image response: {}", submitResponse.body());
            return Result.error("图片服务没有返回任务 ID");
        }
        log.info("DashScope text2image task submitted: taskId={}", taskId);

        // 2) 轮询直到完成
        Duration pollTimeout = safeTimeout(aiProperties.getImageTimeout());
        long deadline = System.currentTimeMillis() + pollTimeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);

            HttpRequest queryRequest = HttpRequest.newBuilder(URI.create(TASK_QUERY_BASE + taskId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> queryResponse = httpClient.send(queryRequest, HttpResponse.BodyHandlers.ofString());

            if (queryResponse.statusCode() != 200) {
                log.warn("DashScope task query failed, taskId={}, status={}", taskId, queryResponse.statusCode());
                continue;
            }

            JsonNode queryRoot = objectMapper.readTree(queryResponse.body());
            String taskStatus = queryRoot.path("output").path("task_status").asText("");

            log.debug("DashScope task poll: taskId={}, status={}", taskId, taskStatus);

            if ("SUCCEEDED".equals(taskStatus)) {
                return parseText2ImageResult(queryRoot);
            } else if ("FAILED".equals(taskStatus)) {
                String message = queryRoot.path("output").path("message").asText("未知错误");
                log.warn("DashScope text2image task failed, taskId={}, message={}", taskId, message);
                return Result.error("图片生成失败：" + message);
            }
            // PENDING / RUNNING → continue
        }

        log.warn("DashScope text2image task timed out, taskId={}", taskId);
        return Result.error("图片生成超时，请稍后重试");
    }

    private ObjectNode buildText2ImageBody(String model, String prompt, String size) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("prompt", prompt);
        body.set("input", input);

        ObjectNode parameters = objectMapper.createObjectNode();
        // wanx 系列使用 "1024*1024" 格式（星号分隔）
        parameters.put("size", size.replace('x', '*').replace('X', '*'));
        parameters.put("n", 1);
        body.set("parameters", parameters);

        return body;
    }

    private Result parseText2ImageResult(JsonNode root) throws Exception {
        JsonNode output = root.path("output");
        JsonNode results = output.path("results");
        if (results.isArray() && !results.isEmpty()) {
            String imageUrl = results.get(0).path("url").asText("");
            if (!imageUrl.isBlank()) {
                return downloadImage(imageUrl);
            }
        }
        log.warn("No image URL in text2image result: {}", root);
        return Result.error("图片服务没有返回有效图片");
    }

    // ========== 通用方法 ==========

    private Result handleHttpError(String model, HttpResponse<String> response) {
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            log.warn("DashScope image authentication failed");
            return Result.error("图片服务认证失败，请联系管理员");
        }
        log.warn("DashScope image request failed, model={}, status={}, body={}",
                model, response.statusCode(), response.body());
        return Result.error("图片生成暂时没有响应，请稍后重试");
    }

    private Result downloadImage(String imageUrl) throws Exception {
        HttpRequest download = HttpRequest.newBuilder(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> downloadResponse = httpClient.send(download,
                HttpResponse.BodyHandlers.ofByteArray());
        if (downloadResponse.statusCode() == 200 && downloadResponse.body().length > 0) {
            byte[] bytes = downloadResponse.body();
            if (bytes.length <= MAX_IMAGE_BYTES) {
                log.info("DashScope image downloaded, url={}, bytes={}", imageUrl, bytes.length);
                return Result.image(bytes);
            }
        }
        log.warn("Failed to download image from {}", imageUrl);
        return Result.error("图片下载失败");
    }

    private static Duration safeTimeout(Duration timeout) {
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofMinutes(15)
                : timeout;
    }
}
