package com.wechatbot.fashion.bot.audio;

import com.wechatbot.fashion.bot.config.AliyunTtsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 调用阿里云百炼非实时 TTS，下载完整 MP3 后交给 iLink 作为文件发送。
 * 作为 MiMo TTS 的回退服务使用。
 */
@Service
public class AliyunDashScopeTtsService implements TextToSpeechService {

    private static final Logger log = LoggerFactory.getLogger(AliyunDashScopeTtsService.class);

    private final AliyunTtsProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AliyunDashScopeTtsService(AliyunTtsProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(safeTimeout(properties.getTimeout()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Optional<SynthesizedAudio> synthesize(String text, String modelId, String voiceId) {
        if (!properties.isEnabled() || !properties.isConfigured()) {
            return Optional.empty();
        }
        String safeText = text == null ? "" : text.trim();
        if (safeText.isBlank()) {
            throw new SpeechSynthesisException("Aliyun TTS text is blank");
        }
        if (safeText.length() > properties.getMaxTextLength()) {
            throw new SpeechSynthesisException("Aliyun TTS text exceeds configured limit");
        }

        try {
            HttpResponse<String> response = httpClient.send(buildRequest(
                            safeText, effectiveModel(modelId), effectiveVoice(voiceId)),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpFailure(response.statusCode(), response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode audio = root.path("output").path("audio");
            byte[] bytes = readAudio(audio);
            if (bytes.length == 0 || bytes.length > properties.getMaxAudioBytes()) {
                throw new SpeechSynthesisException("Aliyun TTS audio size is invalid");
            }
            String requestId = root.path("request_id").asText("");
            log.info("Aliyun TTS completed, bytes={}, requestIdPresent={}", bytes.length, !requestId.isBlank());
            return Optional.of(new SynthesizedAudio("answer." + normalizedFormat(), bytes));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SpeechSynthesisException("Aliyun TTS request interrupted", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new SpeechSynthesisException("Aliyun TTS request failed", exception);
        }
    }

    HttpRequest buildRequest(String text, String modelId, String voiceId) throws IOException {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("text", text);
        input.put("voice", voiceId);
        input.put("format", normalizedFormat());
        input.put("sample_rate", properties.getSampleRate());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelId);
        body.set("input", input);

        return HttpRequest.newBuilder(URI.create(properties.getBaseUrl()))
                .timeout(safeTimeout(properties.getTimeout()))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private byte[] readAudio(JsonNode audio) throws IOException, InterruptedException {
        String data = audio.path("data").asText("");
        if (!data.isBlank()) {
            return Base64.getDecoder().decode(data);
        }
        String url = audio.path("url").asText("");
        if (url.isBlank()) {
            throw new SpeechSynthesisException("Aliyun TTS returned no audio URL or data");
        }
        HttpRequest download = HttpRequest.newBuilder(URI.create(url))
                .timeout(safeTimeout(properties.getTimeout()))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(download, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SpeechSynthesisException("Aliyun TTS audio download failed");
        }
        return response.body();
    }

    private SpeechSynthesisException httpFailure(int status, String body) {
        String code = "";
        try {
            code = objectMapper.readTree(body).path("code").asText("");
        } catch (IOException ignored) {
            // 错误体不是 JSON 时只按 HTTP 状态分类，不记录完整响应。
        }
        if (status == 401 || status == 403) {
            return new SpeechSynthesisException(
                    "Aliyun TTS authentication failed: " + code,
                    "阿里云语音服务认证失败，请联系管理员。",
                    null
            );
        }
        if (status == 429 || code.toLowerCase().contains("quota")) {
            return new SpeechSynthesisException(
                    "Aliyun TTS quota or rate limit: " + code,
                    "阿里云语音额度不足或请求过快，请稍后重试。",
                    null
            );
        }
        return new SpeechSynthesisException("Aliyun TTS HTTP " + status + ": " + code);
    }

    private String normalizedFormat() {
        return "wav".equalsIgnoreCase(properties.getFormat()) ? "wav" : "mp3";
    }

    private String effectiveVoice(String voiceId) {
        return voiceId == null || voiceId.isBlank() ? properties.getVoice() : voiceId.trim();
    }

    private String effectiveModel(String modelId) {
        return modelId == null || modelId.isBlank() ? properties.getModel() : modelId.trim();
    }

    private static Duration safeTimeout(Duration timeout) {
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(90)
                : timeout;
    }
}
