package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.config.AiProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 不消耗真实额度，验证官方 SDK 发出的 Images API 路径、参数和 Base64 解析。 */
class AiImageGenerationServiceContractTest {

    @Test
    void sendsGptImage2RequestToOpenAiImagesEndpoint() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"created\":1,\"data\":[{\"b64_json\":\"AQID\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AiProperties properties = new AiProperties();
            properties.setImageModel("gpt-image-2");
            properties.setImageSize("1024x1024");
            properties.setImageQuality("high");
            OpenAIClient client = OpenAIOkHttpClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .apiKey("test-only")
                    .build();

            AiImageGenerationService.Result result = new AiImageGenerationService(client, properties)
                    .generate("image-contract-user", "一只橘猫站在月球上");

            assertThat(result.hasImage()).isTrue();
            assertThat(result.imageBytes()).containsExactly(1, 2, 3);
            assertThat(path.get()).isEqualTo("/v1/images/generations");
            assertThat(body.get()).contains("gpt-image-2", "response_format", "b64_json", "output_format", "png");
        } finally {
            server.stop(0);
        }
    }
}
