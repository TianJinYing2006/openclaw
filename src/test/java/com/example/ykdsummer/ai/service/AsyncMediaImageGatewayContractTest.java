package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.ImageOpenAiClientProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 用本机假网关验证异步媒体协议，不访问真实图片服务或使用真实 Key。 */
class AsyncMediaImageGatewayContractTest {

    @Test
    void sendsReferenceImageThenPollsAndDownloadsTheResult() throws Exception {
        AtomicReference<String> request = new AtomicReference<>("");
        AtomicReference<String> authorization = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/media/generate", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            json(exchange, "{\"task_id\":\"test-task\"}");
        });
        server.createContext("/v1/media/status", exchange -> json(exchange,
                "{\"task_id\":\"test-task\",\"state\":\"success\",\"is_final\":true,"
                        + "\"result_url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/result.png\"}"));
        server.createContext("/result.png", exchange -> bytes(exchange, new byte[]{1, 2, 3, 4}));
        server.start();
        try {
            ImageOpenAiClientProperties image = new ImageOpenAiClientProperties();
            image.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            image.setApiKey("local-test-key");
            AiProperties ai = new AiProperties();
            ai.setImageModel("gpt-image-2");
            AsyncMediaImageGateway gateway = new AsyncMediaImageGateway(image, ai, new ObjectMapper(), RestClient.builder().build());

            AsyncImageEditGateway.EditResult result = gateway.edit("把围巾改成蓝色", "https://signed.example/original.png");

            assertThat(result.hasImage()).isTrue();
            assertThat(result.imageBytes()).containsExactly(1, 2, 3, 4);
            assertThat(request.get()).contains("gpt-image-2", "把围巾改成蓝色", "https://signed.example/original.png");
            assertThat(authorization.get()).isEqualTo("Bearer local-test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOneTemporaryStatusFailureBeforeTheTaskSucceeds() throws Exception {
        AtomicInteger statusCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/media/generate", exchange -> json(exchange, "{\"task_id\":\"test-task\"}"));
        server.createContext("/v1/media/status", exchange -> {
            if (statusCalls.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(502, -1);
                exchange.close();
                return;
            }
            json(exchange, "{\"state\":\"success\",\"is_final\":true,\"result_url\":\"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/result.png\"}");
        });
        server.createContext("/result.png", exchange -> bytes(exchange, new byte[]{9}));
        server.start();
        try {
            ImageOpenAiClientProperties image = new ImageOpenAiClientProperties();
            image.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            image.setApiKey("local-test-key");
            AiProperties ai = new AiProperties();
            ai.setImageTimeout(java.time.Duration.ofSeconds(2));
            ai.setImagePollInterval(java.time.Duration.ofMillis(10));

            var result = new AsyncMediaImageGateway(image, ai, new ObjectMapper(), RestClient.builder().build())
                    .edit("改图", "https://signed.example/input.png");

            assertThat(result.imageBytes()).containsExactly(9);
            assertThat(statusCalls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        bytes(exchange, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
