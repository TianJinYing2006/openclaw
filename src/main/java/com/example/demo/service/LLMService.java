package com.example.demo.service;

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

@Service
public class LLMService {

    private static final Logger log = LoggerFactory.getLogger(com.example.demo.service.LLMService.class);
    private static final String DEFAULT_API_URL = "https://api.xiaomimimo.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "mimo-v2.5-pro";

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

    public LLMService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调用 LLM 对话接口
     * @param userMessage 用户输入
     * @return 模型回复文本
     */
    public String chat(String userMessage) {
        String key = apiKey != null ? apiKey.trim() : "";
        if (key.isBlank()) {
            log.warn("LLM API Key 未配置，请在 application.properties 中设置 llm.api-key");
            return "AI 服务未配置，请联系管理员设置 API Key";
        }

        String targetUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl.trim() : DEFAULT_API_URL;
        String targetModel = (model != null && !model.isBlank()) ? model.trim() : DEFAULT_MODEL;

        try {
            // 构建请求体
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", targetModel);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);

            ArrayNode messages = body.putArray("messages");
            // 系统提示词
            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "你是一个智能助手，请用中文简洁友好地回答问题。");
            // 用户消息
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);

            String jsonBody = objectMapper.writeValueAsString(body);

            // 发送请求
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
            log.info("LLM API 请求: status={}, elapsed={}ms, model={}", statusCode, elapsedMs, targetModel);

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

    /**
     * 解析 OpenAI/DeepSeek 格式的响应 JSON
     */
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

