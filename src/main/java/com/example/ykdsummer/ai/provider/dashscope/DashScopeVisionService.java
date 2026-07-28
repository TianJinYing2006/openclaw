package com.example.ykdsummer.ai.provider.dashscope;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.OpenAiClientProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * 基于 Chat Completions API（OpenAI 兼容模式）的图片视觉识别服务。
 *
 * <p>将图片以 Base64 Data URL 形式嵌入请求体，调用兼容模式下
 * 多模态模型进行图片内容理解。使用与主聊天相同的 API 配置
 * （{@link OpenAiClientProperties}），无需额外配置。</p>
 *
 * <p>绕开 OpenAI Responses API 协议，因为大多数第三方供应商不支持该协议。
 * Chat Completions 的 {@code content} 数组格式支持 {@code image_url} 类型，
 * 百炼（Qwen 系列）和大多数兼容供应商均已实现。</p>
 */
@Service
public class DashScopeVisionService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeVisionService.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final AiProperties aiProperties;
    private final String apiKey;
    private final String chatCompletionsUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DashScopeVisionService(
            AiProperties aiProperties,
            OpenAiClientProperties openAiClientProperties
    ) {
        this.aiProperties = aiProperties;
        this.apiKey = openAiClientProperties.getApiKey();
        // 从 openai.base-url 拼接 chat/completions，确保与主聊天使用同一供应商
        String base = openAiClientProperties.getBaseUrl();
        this.chatCompletionsUrl = base.endsWith("/")
                ? base + "chat/completions"
                : base + "/chat/completions";
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 识别图片内容。
     *
     * @param imageBytes 图片原始字节
     * @param mediaType  媒体类型，如 {@code image/png}、{@code image/jpeg}
     * @param question   对图片的具体问题；为空时做通用描述
     * @return 模型返回的文本描述
     */
    public String inspect(byte[] imageBytes, String mediaType, String question) {
        if (apiKey == null || apiKey.isBlank() || "not-configured".equals(apiKey)) {
            log.warn("Vision disabled: openai.api-key not configured");
            return "图片识别服务未配置 API Key";
        }
        try {
            String bodyStr = buildRequestBody(imageBytes, mediaType, question);
            log.debug("Vision request: endpoint={}, model={}, imageBytes={}, questionLength={}",
                    chatCompletionsUrl, effectiveModel(), imageBytes.length,
                    question != null ? question.length() : 0);

            HttpRequest request = HttpRequest.newBuilder(URI.create(chatCompletionsUrl))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() != 200) {
                log.warn("Vision request failed, status={}, body={}",
                        httpResponse.statusCode(), truncate(httpResponse.body(), 500));
                return "图片识别服务暂时没有响应，请稍后重试";
            }

            return parseResponse(httpResponse.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Vision request interrupted");
            return "图片识别请求被中断";
        } catch (Exception e) {
            log.warn("Vision request failed, type={}, message={}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            return "图片识别暂时不可用，请稍后重试";
        }
    }

    /**
     * 构造 Chat Completions 请求体，将图片以 Base64 Data URL 嵌入 content 数组。
     */
    private String buildRequestBody(byte[] imageBytes, String mediaType, String question) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", effectiveModel());

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");

        ArrayNode content = objectMapper.createArrayNode();

        // 文本部分
        String prompt = "请只根据附图回答。" + (question == null || question.isBlank()
                ? "请概括主体、人物/物体、颜色、风格、构图和可见文字，供后续图片修改使用。"
                : question.strip());

        ObjectNode textPart = objectMapper.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        content.add(textPart);

        // 图片部分：Base64 Data URL
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        ObjectNode imagePart = objectMapper.createObjectNode();
        imagePart.put("type", "image_url");
        ObjectNode imageUrl = objectMapper.createObjectNode();
        imageUrl.put("url", "data:" + mediaType + ";base64," + base64);
        imagePart.set("image_url", imageUrl);
        content.add(imagePart);

        userMessage.set("content", content);
        messages.add(userMessage);
        body.set("messages", messages);

        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize vision request body", e);
        }
    }

    /**
     * 解析 Chat Completions 响应，提取模型回复文本。
     */
    private String parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            String text = choices.get(0).path("message").path("content").asText("");
            if (!text.isBlank()) {
                return text;
            }
        }
        log.warn("Empty vision response: {}", truncate(responseBody, 300));
        return "图片识别未能返回有效描述";
    }

    private String effectiveModel() {
        // 优先使用视觉专用模型；未配置时回退到主聊天模型
        String vision = aiProperties.getVisionModel();
        if (vision != null && !vision.isBlank()) {
            return vision;
        }
        String fallback = aiProperties.getModel();
        return (fallback != null && !fallback.isBlank()) ? fallback : "qwen-vl-max";
    }

    private static String truncate(String s, int maxLen) {
        return (s != null && s.length() > maxLen) ? s.substring(0, maxLen) + "..." : s;
    }
}
