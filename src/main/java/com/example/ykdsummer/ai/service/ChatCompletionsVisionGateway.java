package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.ChatCompletionsConnectionProperties;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Sends one or more images as OpenAI-compatible Chat Completions image_url content. */
@Service
public class ChatCompletionsVisionGateway implements VisionChatGateway {
    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsVisionGateway.class);
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final ChatCompletionsConnectionProperties connection;
    private final AiProperties properties;

    @Autowired
    public ChatCompletionsVisionGateway(
            ObjectMapper objectMapper,
            ChatCompletionsConnectionProperties connection,
            AiProperties properties
    ) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), objectMapper, connection, properties);
    }

    ChatCompletionsVisionGateway(
            HttpClient client,
            ObjectMapper objectMapper,
            ChatCompletionsConnectionProperties connection,
            AiProperties properties
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.connection = connection;
        this.properties = properties;
    }

    public String inspect(String prompt, AiImage image) {
        if (image == null || image.bytes().length == 0) {
            throw new IllegalArgumentException("Image inspection requires non-empty image bytes");
        }
        return request(List.of(), prompt, List.of(image), null, true).text();
    }

    /** Sends a short-lived HTTPS reference so the provider can fetch an OSS object directly. */
    public String inspect(String prompt, String imageUrl) {
        return request(List.of(), prompt, List.of(), externalImageUrl(imageUrl), null, true).text();
    }

    /**
     * Used for multi-frame video analysis. DashScope's OpenAI-compatible endpoint supports image_url
     * in Chat Completions, whereas it does not provide the OpenAI Responses API used by the old route.
     */
    @Override
    public LlmGateway.ModelReply generate(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            AiRequestBudget budget
    ) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("Vision chat completion requires at least one image");
        }
        Completion completion = request(history, prompt, images, budget, false);
        return new LlmGateway.ModelReply(
                completion.text(), completion.model(), List.of(), completion.usage(), "chat-completions-vision");
    }

    private Completion request(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            AiRequestBudget budget,
            boolean inspection
    ) {
        return request(history, prompt, images, null, budget, inspection);
    }

    private Completion request(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            String remoteImageUrl,
            AiRequestBudget budget,
            boolean inspection
    ) {
        String apiKey = connection.getApiKey();
        if (apiKey == null || apiKey.isBlank() || "not-configured".equals(apiKey)) {
            throw new AiGatewayException(AiGatewayException.Kind.AUTHENTICATION);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(chatCompletionsUri(connection.getBaseUrl()))
                    .timeout(inspection ? properties.getVisionTimeout() : properties.getTimeout())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(requestBody(history, prompt, images, remoteImageUrl, budget, inspection)), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Vision chat completion failed, status={}, providerCode={}", response.statusCode(),
                        providerCode(response.body()));
                throw new AiGatewayException(response.statusCode() == 401 || response.statusCode() == 403
                        ? AiGatewayException.Kind.AUTHENTICATION
                        : AiGatewayException.Kind.TEMPORARY_UNAVAILABLE);
            }
            String text = responseText(response.body());
            if (text.isBlank()) {
                throw new AiGatewayException(AiGatewayException.Kind.EMPTY_RESPONSE);
            }
            JsonNode body = objectMapper.readTree(response.body());
            String model = body.path("model").asText(properties.getModel());
            log.info("Vision chat completion completed, model={}", model);
            return new Completion(text, model, usage(body));
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiGatewayException(AiGatewayException.Kind.TEMPORARY_UNAVAILABLE, exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new AiGatewayException(AiGatewayException.Kind.TEMPORARY_UNAVAILABLE, exception);
        }
    }

    private JsonNode requestBody(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            String remoteImageUrl,
            AiRequestBudget budget,
            boolean inspection
    ) {
        var root = objectMapper.createObjectNode();
        // 视觉请求优先用独立视觉模型（如 qwen-vl-max）；未配置时回退主对话模型
        String vision = properties.getVisionModel();
        root.put("model", vision == null || vision.isBlank() ? properties.getModel() : vision);
        int maxTokens = budget == null
                ? (inspection ? properties.getVisionMaxCompletionTokens() : properties.getMaxCompletionTokens())
                : budget.maxOutputTokens();
        if (maxTokens > 0) root.put("max_tokens", maxTokens);
        String reasoningEffort = inspection ? properties.getVisionReasoningEffort() : properties.getReasoningEffort();
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            root.put("reasoning_effort", reasoningEffort.strip());
        }
        var messages = root.putArray("messages");
        for (ConversationMessage previous : history == null ? List.<ConversationMessage>of() : history) {
            if (previous == null || previous.text() == null || previous.text().isBlank()) continue;
            messages.addObject()
                    .put("role", previous.role() == ConversationMessage.Role.ASSISTANT ? "assistant" : "user")
                    .put("content", previous.text());
        }
        var message = messages.addObject();
        message.put("role", "user");
        var content = message.putArray("content");
        content.addObject().put("type", "text").put("text", prompt == null || prompt.isBlank() ? "请描述图片内容。" : prompt);
        if (remoteImageUrl != null) {
            content.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", remoteImageUrl).put("detail", "low");
        }
        for (AiImage image : images) {
            if (image == null || image.bytes().length == 0) continue;
            var imageUrl = content.addObject().put("type", "image_url").putObject("image_url");
            String mediaType = image.mediaType() == null || !image.mediaType().startsWith("image/")
                    ? "image/jpeg" : image.mediaType();
            imageUrl.put("url", "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(image.bytes()));
            if (image.detail() == AiImage.Detail.LOW) imageUrl.put("detail", "low");
        }
        return root;
    }

    private String responseText(String body) throws IOException {
        JsonNode content = objectMapper.readTree(body).path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText().strip();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : content) {
                String value = item.path("text").asText("");
                if (!value.isBlank()) text.append(value);
            }
            return text.toString().strip();
        }
        return "";
    }

    private String providerCode(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String code = error.path("code").asText("");
            return code.isBlank() ? "unknown" : code;
        } catch (IOException ignored) {
            return "unreadable";
        }
    }

    private static AiModelUsage usage(JsonNode body) {
        JsonNode usage = body.path("usage");
        if (!usage.isObject()) return AiModelUsage.unknown();
        long prompt = usage.path("prompt_tokens").asLong(0L);
        long completion = usage.path("completion_tokens").asLong(0L);
        long total = usage.path("total_tokens").asLong(0L);
        return prompt == 0L && completion == 0L && total == 0L
                ? AiModelUsage.unknown()
                : AiModelUsage.reported(prompt, completion, total);
    }

    private static URI chatCompletionsUri(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.strip();
        if (normalized.isBlank()) throw new IllegalArgumentException("Chat Completions base URL is required");
        normalized = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        if (!normalized.endsWith("/v1")) normalized += "/v1";
        return URI.create(normalized + "/chat/completions");
    }

    private static String externalImageUrl(String value) {
        String clean = value == null ? "" : value.strip();
        try {
            URI uri = URI.create(clean);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if ((!"https".equals(scheme) && !"http".equals(scheme)) || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Remote image URL must be HTTP(S)");
            }
            return uri.toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Remote image URL must be HTTP(S)", exception);
        }
    }

    private record Completion(String text, String model, AiModelUsage usage) { }
}
