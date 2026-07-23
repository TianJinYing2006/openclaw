package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.ImageOpenAiClientProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            String taskId = taskId(created);
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
                waitForNextPoll(deadline);
                continue;
            }
            boolean done = firstBoolean(status, "is_final", "isFinal", "completed", "done");
            String state = firstText(status, "state", "status");
            String url = firstText(status, "result_url", "resultUrl", "url");
            if (done || terminalState(state)) {
                if (!successfulState(state) && url.isBlank()) {
                    return EditResult.error("图片编辑失败：" + safeError(firstText(status,
                            "error_message", "errorMessage", "message", "error")));
                }
                return url.isBlank() ? EditResult.error("图片编辑服务没有返回结果图片") : download(url);
            }
            waitForNextPoll(deadline);
        }
        return EditResult.error(transientFailures > 0 ? "图片编辑状态查询超时，请稍后重试" : "图片编辑超时，请稍后重试");
    }

    private void waitForNextPoll(Instant deadline) throws InterruptedException {
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMillis <= 0) {
            return;
        }
        long configuredMillis = Math.max(1L, aiProperties.getImagePollInterval().toMillis());
        Thread.sleep(Math.min(configuredMillis, remainingMillis));
    }

    private EditResult immediateResult(JsonNode created) {
        String url = firstText(created, "result_url", "resultUrl", "url");
        String b64 = firstText(created, "b64_json", "b64Json");
        if (!b64.isBlank()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(b64);
                return valid(bytes) ? EditResult.success(bytes, null) : EditResult.error("图片编辑返回了无效图片");
            } catch (IllegalArgumentException exception) {
                return EditResult.error("图片编辑返回了无效图片");
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

    private static String taskId(JsonNode response) {
        String taskId = firstText(response, "task_id", "taskId", "taskID");
        return taskId.isBlank() ? firstText(response, "id") : taskId;
    }

    /** 兼容中转常见的 data/result 对象包装，同时避免把供应商响应格式写死在业务层。 */
    private static String firstText(JsonNode node, String... names) {
        return firstText(node, Set.of(names));
    }

    private static String firstText(JsonNode node, Set<String> names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isObject()) {
            for (String name : names) {
                JsonNode value = node.get(name);
                if (value != null && value.isValueNode() && !value.asText("").isBlank()) {
                    return value.asText();
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                String value = firstText(fields.next().getValue(), names);
                if (!value.isBlank()) {
                    return value;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String value = firstText(item, names);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private static boolean firstBoolean(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            for (String name : names) {
                JsonNode value = node.get(name);
                if (value != null && value.asBoolean(false)) {
                    return true;
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                if (firstBoolean(fields.next().getValue(), names)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (firstBoolean(item, names)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean terminalState(String state) {
        return successfulState(state) || "failed".equalsIgnoreCase(state) || "error".equalsIgnoreCase(state)
                || "cancelled".equalsIgnoreCase(state) || "canceled".equalsIgnoreCase(state);
    }

    private static boolean successfulState(String state) {
        return "success".equalsIgnoreCase(state) || "succeeded".equalsIgnoreCase(state)
                || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state);
    }
    private static boolean valid(byte[] bytes) { return bytes != null && bytes.length > 0 && bytes.length <= MAX_IMAGE_BYTES; }
    private static String safeError(String value) { return value == null || value.isBlank() ? "请稍后重试" : value.replace('\n', ' ').strip(); }
}
