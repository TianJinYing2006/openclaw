package com.example.demo.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.demo.model.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Service
public class VideoUnderstandingService {

    private static final String DEFAULT_API_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_MODEL = "qwen3-omni-flash";
    private static final int MAX_DATA_URI_BYTES = 10 * 1024 * 1024;
    private static final String DEFAULT_PROMPT =
            "请综合分析视频画面和声音，按时间顺序描述场景、人物、动作、对白、旁白和关键事件。"
                    + "如果有内容看不清或听不清，请明确说明，不要猜测或编造。";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.video-api-url:}")
    private String apiUrl;

    @Value("${llm.video-model:qwen3-omni-flash}")
    private String model;

    @Value("${llm.max-tokens:2048}")
    private int maxTokens;

    @Value("${llm.system-prompt:你是一个智能助手，请用中文简洁友好地回答问题。}")
    private String systemPrompt;

    public VideoUnderstandingService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String analyze(List<Message> history, String prompt, byte[] videoBytes, String mimeType)
            throws IOException, InterruptedException {
        if (videoBytes == null || videoBytes.length == 0) {
            throw new IllegalArgumentException("视频数据为空");
        }
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isBlank()) {
            throw new IllegalStateException("LLM API Key 未配置");
        }

        ObjectNode body = buildRequest(history, prompt, videoBytes, mimeType);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(valueOrDefault(apiUrl, DEFAULT_API_URL)))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("视频识别失败: HTTP " + response.statusCode() + ", " + response.body());
        }

        String result = parseStreamingResponse(response.body());
        if (result.isBlank()) {
            result = parseJsonResponse(response.body());
        }
        if (result.isBlank()) {
            throw new IOException("视频识别未返回文本: " + response.body());
        }
        return result.trim();
    }

    private ObjectNode buildRequest(List<Message> history, String prompt, byte[] videoBytes, String mimeType) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", valueOrDefault(model, DEFAULT_MODEL));
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.put("enable_thinking", false);
        body.putArray("modalities").add("text");

        ArrayNode messages = body.putArray("messages");
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);

        if (history != null) {
            for (Message message : history) {
                ObjectNode historyMessage = messages.addObject();
                historyMessage.put("role", message.getRole());
                historyMessage.put("content", message.getContent());
            }
        }

        String actualMimeType = valueOrDefault(mimeType, "video/mp4");
        String actualPrompt = prompt == null || prompt.isBlank()
                ? DEFAULT_PROMPT
                : "请综合视频画面和声音回答下面的问题。无法确定的内容请明确说明，不要猜测。问题："
                    + prompt.trim();
        String base64 = Base64.getEncoder().encodeToString(videoBytes);
        if (base64.length() > MAX_DATA_URI_BYTES) {
            throw new IllegalArgumentException("视频 Base64 数据超过 10MB 限制");
        }

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode content = userMessage.putArray("content");

        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", actualPrompt);

        ObjectNode videoPart = content.addObject();
        videoPart.put("type", "video_url");
        videoPart.putObject("video_url")
                .put("url", "data:" + actualMimeType + ";base64," + base64);

        return body;
    }

    private String parseStreamingResponse(String responseBody) {
        StringBuilder result = new StringBuilder();
        for (String line : responseBody.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring("data:".length()).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(data);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode choice = choices.get(0);
                String text = extractContent(choice.path("delta").path("content"));
                if (text.isBlank()) {
                    text = extractContent(choice.path("message").path("content"));
                }
                result.append(text);
            } catch (Exception ignored) {
            }
        }
        return result.toString();
    }

    private String parseJsonResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return "";
            }
            JsonNode choice = choices.get(0);
            String text = extractContent(choice.path("message").path("content"));
            if (text.isBlank()) {
                text = extractContent(choice.path("delta").path("content"));
            }
            return text;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String extractContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                String value = part.path("text").asText("");
                if (!value.isBlank()) {
                    text.append(value);
                }
            }
            return text.toString();
        }
        return "";
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
