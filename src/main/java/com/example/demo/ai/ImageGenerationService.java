package com.example.demo.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class ImageGenerationService {

    private static final String DEFAULT_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
    private static final String DEFAULT_TASK_URL =
            "https://dashscope.aliyuncs.com/api/v1/tasks";
    private static final String DEFAULT_MODEL = "wanx2.1-t2i-turbo";
    private static final int MAX_POLL_ATTEMPTS = 60;
    private static final long POLL_INTERVAL_MILLIS = 2_000L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.image-api-url:}")
    private String apiUrl;

    @Value("${llm.image-task-url:}")
    private String taskUrl;

    @Value("${llm.image-model:wanx2.1-t2i-turbo}")
    private String model;

    @Value("${llm.image-size:1024*1024}")
    private String imageSize;

    public ImageGenerationService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public byte[] generate(String prompt) throws IOException, InterruptedException {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isBlank()) {
            throw new IllegalStateException("LLM API Key 未配置");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("生图提示词不能为空");
        }

        String taskId = submitTask(key, prompt.trim());
        String imageUrl = waitForResult(key, taskId);
        return downloadImage(imageUrl);
    }

    private String submitTask(String key, String prompt) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", valueOrDefault(model, DEFAULT_MODEL));
        body.putObject("input").put("prompt", prompt);
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("size", valueOrDefault(imageSize, "1024*1024"));
        parameters.put("n", 1);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(valueOrDefault(apiUrl, DEFAULT_API_URL)))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("生图任务提交失败: HTTP " + response.statusCode() + ", " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String taskId = root.path("output").path("task_id").asText("");
        if (taskId.isBlank()) {
            throw new IOException("生图接口未返回 task_id: " + response.body());
        }
        return taskId;
    }

    private String waitForResult(String key, String taskId) throws IOException, InterruptedException {
        String endpoint = valueOrDefault(taskUrl, DEFAULT_TASK_URL) + "/" + taskId;
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + key)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("查询生图任务失败: HTTP " + response.statusCode() + ", " + response.body());
            }

            JsonNode output = objectMapper.readTree(response.body()).path("output");
            String status = output.path("task_status").asText("");
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                String imageUrl = output.path("results").path(0).path("url").asText("");
                if (imageUrl.isBlank()) {
                    throw new IOException("生图任务成功但未返回图片 URL: " + response.body());
                }
                return imageUrl;
            }
            if ("FAILED".equalsIgnoreCase(status) || "UNKNOWN".equalsIgnoreCase(status)) {
                String message = output.path("message").asText("未知错误");
                throw new IOException("生图任务失败: " + message);
            }

            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IOException("生图任务超时，请稍后重试");
    }

    private byte[] downloadImage(String imageUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0) {
            throw new IOException("下载生成图片失败: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
