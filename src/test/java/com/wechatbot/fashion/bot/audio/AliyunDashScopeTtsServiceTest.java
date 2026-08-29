package com.wechatbot.fashion.bot.audio;

import com.wechatbot.fashion.bot.config.AliyunTtsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AliyunDashScopeTtsServiceTest {

    @Test
    void sendsExpectedRequestAndReadsBase64Mp3() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        byte[] mp3 = {0x49, 0x44, 0x33, 3, 0, 0, 1, 2, 3};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/tts", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String json = "{\"request_id\":\"req\",\"output\":{\"audio\":{\"data\":\""
                    + Base64.getEncoder().encodeToString(mp3) + "\"}}}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AliyunTtsProperties properties = configuredProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/tts");
            TextToSpeechService.SynthesizedAudio audio =
                    new AliyunDashScopeTtsService(properties)
                            .synthesize("你好", "cosyvoice-v3-flash", "longwan_v3")
                            .orElseThrow();

            assertThat(audio.fileName()).isEqualTo("answer.mp3");
            assertThat(audio.bytes()).containsExactly(mp3);
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            JsonNode sent = mapper.readTree(requestBody.get());
            assertThat(sent.path("model").asText()).isEqualTo("cosyvoice-v3-flash");
            assertThat(sent.path("input").path("text").asText()).isEqualTo("你好");
            assertThat(sent.path("input").path("voice").asText()).isEqualTo("longwan_v3");
            assertThat(sent.path("input").path("format").asText()).isEqualTo("mp3");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledServiceDoesNotCallTheCloud() {
        AliyunTtsProperties properties = new AliyunTtsProperties();
        properties.setEnabled(false);
        assertThat(new AliyunDashScopeTtsService(properties)
                .synthesize("你好", "cosyvoice-v3-flash", "longwan_v3")).isEmpty();
    }

    private static AliyunTtsProperties configuredProperties(String baseUrl) {
        AliyunTtsProperties properties = new AliyunTtsProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setBaseUrl(baseUrl);
        return properties;
    }
}
