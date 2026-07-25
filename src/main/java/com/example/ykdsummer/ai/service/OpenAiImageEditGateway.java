package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.ImageOpenAiClientProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Standard OpenAI-compatible image editing gateway.
 *
 * <p>The original image is read from the short-lived OSS URL, then uploaded to
 * {@code /v1/images/edits} as multipart form data. This keeps the OSS bucket
 * private while allowing an image provider to receive the source bytes.</p>
 */
@Service
public class OpenAiImageEditGateway implements AsyncImageEditGateway {

    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;

    private final ImageOpenAiClientProperties imageProperties;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public OpenAiImageEditGateway(
            ImageOpenAiClientProperties imageProperties,
            AiProperties aiProperties,
            ObjectMapper objectMapper
    ) {
        this(imageProperties, aiProperties, objectMapper, HttpClient.newBuilder().build());
    }

    OpenAiImageEditGateway(
            ImageOpenAiClientProperties imageProperties,
            AiProperties aiProperties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.imageProperties = imageProperties;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public EditResult edit(String prompt, String referenceImageUrl) {
        if (!configured()) {
            return EditResult.error("图片编辑服务未配置");
        }
        if (referenceImageUrl == null || referenceImageUrl.isBlank()) {
            return EditResult.error("原图读取地址无效，无法修改图片");
        }
        try {
            SourceImage source = downloadSource(referenceImageUrl);
            HttpResponse<String> response = httpClient.send(
                    buildEditRequest(prompt, source), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return EditResult.error(errorMessage(response.statusCode(), response.body()));
            }
            return parseResult(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return EditResult.error("图片编辑已取消");
        } catch (IOException | IllegalArgumentException exception) {
            return EditResult.error("图片编辑暂时没有响应，请稍后重试");
        }
    }

    private SourceImage downloadSource(String referenceImageUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(referenceImageUrl))
                .timeout(timeout())
                .header("Accept", "image/*")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("source image returned HTTP " + response.statusCode());
        }
        try (InputStream body = response.body()) {
            byte[] bytes = readLimited(body);
            return new SourceImage(bytes, mediaType(response.headers().firstValue("Content-Type").orElse("")));
        }
    }

    private HttpRequest buildEditRequest(String prompt, SourceImage source) throws IOException {
        String boundary = "----YkdImageEdit" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "model", aiProperties.getImageModel());
        writeField(body, boundary, "prompt", safe(prompt));
        writeField(body, boundary, "size", aiProperties.getImageSize());
        writeField(body, boundary, "quality", aiProperties.getImageQuality());
        writeField(body, boundary, "response_format", "b64_json");
        writeFile(body, boundary, "image", "source." + source.extension(), source.mediaType(), source.bytes());
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return HttpRequest.newBuilder(URI.create(baseUrl() + "/images/edits"))
                .timeout(timeout())
                .header("Authorization", "Bearer " + imageProperties.getApiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
    }

    private EditResult parseResult(String body) {
        try {
            JsonNode first = objectMapper.readTree(body == null ? "{}" : body).path("data").path(0);
            String encoded = first.path("b64_json").asText("");
            if (!encoded.isBlank()) {
                byte[] bytes = Base64.getDecoder().decode(encoded);
                return valid(bytes) ? EditResult.success(bytes, null) : EditResult.error("图片编辑返回了无效图片");
            }
            String url = first.path("url").asText("");
            return url.isBlank() ? EditResult.error("图片编辑服务没有返回结果图片") : downloadResult(url);
        } catch (IOException | IllegalArgumentException exception) {
            return EditResult.error("图片编辑返回了无法识别的数据");
        }
    }

    private EditResult downloadResult(String url) {
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(timeout()).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return EditResult.error("图片编辑结果下载失败");
            }
            try (InputStream body = response.body()) {
                byte[] bytes = readLimited(body);
                return EditResult.success(bytes, url);
            }
        } catch (IOException | InterruptedException | IllegalArgumentException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return EditResult.error("图片编辑结果下载失败");
        }
    }

    private String errorMessage(int status, String body) {
        String message = "";
        try {
            message = objectMapper.readTree(body == null ? "{}" : body).path("error").path("message").asText("");
        } catch (IOException ignored) {
            // Keep the provider response out of the user-facing message.
        }
        if (status == 401 || status == 403) return "图片服务认证失败，请联系管理员";
        if (status == 429) return "图片服务请求过快或额度不足，请稍后重试";
        return message.isBlank() ? "图片编辑暂时没有响应，请稍后重试" : "图片编辑失败：" + message;
    }

    private boolean configured() {
        String key = imageProperties.getApiKey();
        return key != null && !key.isBlank() && !"not-configured".equalsIgnoreCase(key);
    }

    private String baseUrl() {
        String value = imageProperties.getBaseUrl();
        if (value == null || value.isBlank()) return "";
        String normalized = value.replaceFirst("/+$", "");
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }

    private Duration timeout() {
        return aiProperties.getImageTimeout() == null ? Duration.ofSeconds(360) : aiProperties.getImageTimeout();
    }

    private static void writeField(ByteArrayOutputStream output, String boundary, String name, String value) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(safe(value).getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(ByteArrayOutputStream output, String boundary, String name, String fileName,
                                  String contentType, byte[] bytes) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_IMAGE_BYTES) throw new IOException("image exceeds maximum size");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String mediaType(String value) {
        String normalized = value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> "image/jpeg";
            case "image/webp" -> "image/webp";
            default -> "image/png";
        };
    }

    private static boolean valid(byte[] bytes) {
        return bytes != null && bytes.length > 0 && bytes.length <= MAX_IMAGE_BYTES;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private record SourceImage(byte[] bytes, String mediaType) {
        private String extension() {
            return switch (mediaType) {
                case "image/jpeg" -> "jpg";
                case "image/webp" -> "webp";
                default -> "png";
            };
        }
    }
}
