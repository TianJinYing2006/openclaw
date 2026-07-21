package com.example.demo.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.demo.model.Message;
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
import java.util.List;

@Service
public class LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMService.class);
    private static final String DEFAULT_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_MODEL = "qwen-plus";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.api-url:}")
    private String apiUrl;

    @Value("${llm.model:}")
    private String model;

    @Value("${llm.temperature:0.7}")
    private double temperature;

    @Value("${llm.max-tokens:2048}")
    private int maxTokens;

    @Value("${llm.vision-model:qwen-vl-plus}")
    private String visionModel;

    @Value("${llm.system-prompt:你是一个智能助手，请用中文简洁友好地回答问题。}")
    private String systemPrompt;

    @Value("${llm.voice-model:qwen-plus}")
    private String voiceModel;

    @Value("${llm.voice-system-prompt:你是一个语音助手，请用简短口语化的方式回答，适合语音播放，每句话不要太长。}")
    private String voiceSystemPrompt;

    public LLMService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String chat(String userMessage) {
        return chat(List.of(), userMessage, false);
    }

    public String chat(List<Message> history, String newMessage) {
        return chat(history, newMessage, false);
    }

    public String chat(List<Message> history, String newMessage, boolean voiceReply) {
        return callLlm(buildTextRequest(history, newMessage));
    }

    public String chatWithImage(List<Message> history, String userText, byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return chat(history, userText);
        }
        return callLlm(buildVisionRequest(history, userText, imageBytes, mimeType));
    }

    private ObjectNode buildTextRequest(List<Message> history, String newMessage) {
        ObjectNode body = objectMapper.createObjectNode();
        String actualModel = (model != null && !model.isBlank()) ? model.trim() : DEFAULT_MODEL;
        body.put("model", actualModel);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        ArrayNode messages = body.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        for (Message msg : history) {
            ObjectNode node = messages.addObject();
            node.put("role", msg.getRole());
            node.put("content", msg.getContent());
        }
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", newMessage);

        return body;
    }

    private ObjectNode buildVisionRequest(List<Message> history, String userText, byte[] imageBytes, String mimeType) {
        ObjectNode body = objectMapper.createObjectNode();
        String actualVisionModel = (visionModel != null && !visionModel.isBlank()) ? visionModel.trim() : "qwen-vl-plus";
        body.put("model", actualVisionModel);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        ArrayNode messages = body.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        for (Message msg : history) {
            ObjectNode node = messages.addObject();
            node.put("role", msg.getRole());
            node.put("content", msg.getContent());
        }
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String actualMimeType = (mimeType != null && !mimeType.isBlank()) ? mimeType : "image/jpeg";
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode contentArray = userMsg.putArray("content");
        ObjectNode textPart = contentArray.addObject();
        textPart.put("type", "text");
        textPart.put("text", (userText != null && !userText.isBlank()) ? userText : "请描述这张图片");
        ObjectNode imagePart = contentArray.addObject();
        imagePart.put("type", "image_url");
        ObjectNode imageUrl = imagePart.putObject("image_url");
        imageUrl.put("url", "data:" + actualMimeType + ";base64," + base64);

        return body;
    }

    private String callLlm(ObjectNode body) {
        String key = apiKey != null ? apiKey.trim() : "";
        if (key.isBlank()) {
            log.warn("LLM API Key 未配置，请在 application.properties 中设置 llm.api-key");
            return "AI 服务未配置，请联系管理员设置 API Key";
        }

        String targetUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl.trim() : DEFAULT_API_URL;

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            String jsonModel = body.get("model").asText();

            long startNanos = System.nanoTime();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            int statusCode = response.statusCode();
            log.info("LLM API 请求: status={}, elapsed={}ms, model={}", statusCode, elapsedMs, jsonModel);

            if (statusCode == 401) {
                return "AI 服务认证失败，请检查 API Key 是否正确";
            }
            if (statusCode == 429) {
                return "AI 服务请求过于频繁，请稍后再试";
            }
            if (statusCode != 200) {
                log.error("LLM API 返回异常: status={}, body={}", statusCode, response.body());
                return "AI 服务暂时不可用，请稍后重试";
            }

            return parseResponse(response.body());

        } catch (java.net.UnknownHostException e) {
            log.error("LLM API DNS 解析失败: {}", e.getMessage());
            return "无法连接 AI 服务（DNS 解析失败），请检查网络";
        } catch (java.net.ConnectException e) {
            log.error("LLM API 连接失败: {}", e.getMessage());
            return "无法连接 AI 服务，请检查网络或代理设置";
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("LLM API 请求超时: {}", e.getMessage());
            return "AI 服务响应超时，请稍后重试";
        } catch (Exception e) {
            log.error("LLM API 请求异常", e);
            return "AI 服务出错了: " + e.getMessage();
        }
    }

    private String parseResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return "AI 服务返回了空结果";
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            return "AI 服务返回了空结果";
        }
        String content = message.get("content").asText();
        return content != null ? content.trim() : "AI 服务返回了空结果";
    }
}
