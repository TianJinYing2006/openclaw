package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.ImageOpenAiClientProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 调用图片网关的媒体异步协议：创建任务 → 轮询终态 → 下载结果。
 * 该类只用于“基于原图修改”，全新生图仍由 OpenAI Images SDK 处理。
 */
@Service
public class AsyncMediaImageGateway implements AsyncImageEditGateway {
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private final ImageOpenAiClientProperties imageProperties;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @org.springframework.beans.factory.annotation.Autowired
    public AsyncMediaImageGateway(ImageOpenAiClientProperties imageProperties, AiProperties aiProperties,
                                  ObjectMapper objectMapper) {
        this(imageProperties, aiProperties, objectMapper, RestClient.builder().build());
    }

    AsyncMediaImageGateway(ImageOpenAiClientProperties imageProperties, AiProperties aiProperties,
                           ObjectMapper objectMapper, RestClient restClient) {
        this.imageProperties = imageProperties;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public EditResult edit(String prompt, String referenceImageUrl) {
        if (referenceImageUrl == null || referenceImageUrl.isBlank()) {
            return EditResult.error("原图读取地址无效，无法修改图片");
        }
        if (!configured()) {
            return EditResult.error("图片编辑服务未配置");
        }
        try {
            JsonNode created = readJson(restClient.post()
                    .uri(baseUrl() + "/media/generate")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + imageProperties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createPayload(prompt, referenceImageUrl))
                    .retrieve().body(String.class));
            EditResult immediate = immediateResult(created);
            if (immediate != null) {
                return immediate;
            }
            String taskId = firstText(created, "task_id", "id");
            if (taskId.isBlank() && created.path("data").isArray() && !created.path("data").isEmpty()) {
                taskId = firstText(created.path("data").get(0), "task_id", "id");
            }
            if (taskId.isBlank()) {
                return EditResult.error("图片编辑服务没有返回任务编号");
            }
            return poll(taskId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return EditResult.error("图片编辑已取消");
        } catch (RuntimeException exception) {
            return EditResult.error("图片编辑暂时没有响应，请稍后重试");
        }
    }

    private EditResult poll(String taskId) throws InterruptedException {
        Instant deadline = Instant.now().plus(aiProperties.getImageTimeout());
        int transientFailures = 0;
        while (Instant.now().isBefore(deadline)) {
            JsonNode status;
            try {
                status = readJson(restClient.get()
                        .uri(baseUrl() + "/media/status?task_id={taskId}", taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + imageProperties.getApiKey())
                        .retrieve().body(String.class));
                transientFailures = 0;
            } catch (RuntimeException transientFailure) {
                // 一次轮询失败不等于异步任务失败；统一截止时间前继续查询。
                transientFailures++;
                Thread.sleep(aiProperties.getImagePollInterval().toMillis());
                continue;
            }
            boolean done = status.path("is_final").asBoolean(false);
            String state = status.path("state").asText("");
            if (done || "success".equalsIgnoreCase(state) || "failed".equalsIgnoreCase(state)) {
                if (!"success".equalsIgnoreCase(state)) {
                    return EditResult.error("图片编辑失败：" + safeError(status.path("error").asText()));
                }
                String url = firstText(status, "result_url", "url");
                return url.isBlank() ? EditResult.error("图片编辑服务没有返回结果图片") : download(url);
            }
            Thread.sleep(aiProperties.getImagePollInterval().toMillis());
        }
        return EditResult.error(transientFailures > 0 ? "图片编辑状态查询超时，请稍后重试" : "图片编辑超时，请稍后重试");
    }

    private EditResult immediateResult(JsonNode created) {
        String url = firstText(created, "result_url", "url");
        if (url.isBlank() && created.path("data").isArray() && !created.path("data").isEmpty()) {
            JsonNode first = created.path("data").get(0);
            url = firstText(first, "url", "result_url");
            String b64 = first.path("b64_json").asText("");
            if (!b64.isBlank()) {
                byte[] bytes = Base64.getDecoder().decode(b64);
                return valid(bytes) ? EditResult.success(bytes, null) : EditResult.error("图片编辑返回了无效图片");
            }
        }
        return url.isBlank() ? null : download(url);
    }

    private EditResult download(String url) {
        try {
            byte[] bytes = restClient.get().uri(URI.create(url)).retrieve().body(byte[].class);
            return valid(bytes) ? EditResult.success(bytes, url) : EditResult.error("图片编辑返回了无效图片");
        } catch (RuntimeException exception) {
            return EditResult.error("图片编辑结果下载失败");
        }
    }

    private Map<String, Object> createPayload(String prompt, String referenceImageUrl) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("images", List.of(referenceImageUrl));
        params.put("n", 1);
        params.put("quality", aiProperties.getImageQuality());
        params.put("size", "auto");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", aiProperties.getImageModel());
        request.put("prompt", prompt == null ? "" : prompt.strip());
        request.put("params", params);
        return request;
    }

    private boolean configured() {
        String key = imageProperties.getApiKey();
        return key != null && !key.isBlank() && !"not-configured".equalsIgnoreCase(key);
    }

    private String baseUrl() {
        String base = imageProperties.getBaseUrl();
        return base == null ? "" : base.replaceFirst("/+$", "");
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception exception) {
            throw new IllegalStateException("图片网关返回了无法识别的数据", exception);
        }
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }
    private static boolean valid(byte[] bytes) { return bytes != null && bytes.length > 0 && bytes.length <= MAX_IMAGE_BYTES; }
    private static String safeError(String value) { return value == null || value.isBlank() ? "请稍后重试" : value.replace('\n', ' ').strip(); }
}
