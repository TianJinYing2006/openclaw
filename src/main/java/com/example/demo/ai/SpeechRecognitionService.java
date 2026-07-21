package com.example.demo.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

@Service
public class SpeechRecognitionService {

    private static final String DEFAULT_API_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_MODEL = "qwen3-asr-flash";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.asr-api-url:}")
    private String apiUrl;

    @Value("${llm.asr-model:qwen3-asr-flash}")
    private String model;

    public SpeechRecognitionService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String transcribe(byte[] audioBytes, String mimeType) throws IOException, InterruptedException {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("音频数据为空");
        }
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isBlank()) {
            throw new IllegalStateException("LLM API Key 未配置");
        }

        String actualMimeType = mimeType == null || mimeType.isBlank() ? "audio/opus" : mimeType;
        String base64 = Base64.getEncoder().encodeToString(audioBytes);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", valueOrDefault(model, DEFAULT_MODEL));
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode content = userMessage.putArray("content");
        ObjectNode audioPart = content.addObject();
        audioPart.put("type", "input_audio");
        audioPart.putObject("input_audio")
                .put("data", "data:" + actualMimeType + ";base64," + base64);
        body.put("stream", false);
        body.putObject("asr_options").put("enable_itn", true);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(valueOrDefault(apiUrl, DEFAULT_API_URL)))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("语音识别失败: HTTP " + response.statusCode() + ", " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("语音识别返回为空: " + response.body());
        }

        JsonNode message = choices.get(0).path("message");
        String text = extractContent(message.path("content"));
        if (text.isBlank()) {
            text = extractContent(choices.get(0).path("delta").path("content"));
        }
        if (text.isBlank()) {
            throw new IOException("语音识别未返回文本: " + response.body());
        }
        return text.trim();
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
