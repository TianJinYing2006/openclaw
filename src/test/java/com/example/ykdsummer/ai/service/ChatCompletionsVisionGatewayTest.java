package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.ChatCompletionsConnectionProperties;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChatCompletionsVisionGatewayTest {

    @Test
    void sendsLocalImageAsOpenAiCompatibleImageUrl() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"blue\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ChatCompletionsConnectionProperties connection = new ChatCompletionsConnectionProperties();
            connection.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1");
            connection.setApiKey("test-key");
            AiProperties properties = new AiProperties();
            properties.setModel("qwen3.7-plus");
            properties.setMaxCompletionTokens(64);
            properties.setVisionMaxCompletionTokens(256);
            properties.setReasoningEffort("high");
            properties.setTimeout(Duration.ofSeconds(5));
            properties.setVisionTimeout(Duration.ofSeconds(5));
            ChatCompletionsVisionGateway gateway = new ChatCompletionsVisionGateway(
                    HttpClient.newHttpClient(), new ObjectMapper(), connection, properties);

            String result = gateway.inspect("What is the dominant color?", new AiImage("image/png", new byte[] {1, 2, 3}));

            assertThat(result).isEqualTo("blue");
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            JsonNode request = new ObjectMapper().readTree(requestBody.get());
            assertThat(request.path("model").asText()).isEqualTo("qwen3.7-plus");
            assertThat(request.path("max_tokens").asInt()).isEqualTo(256);
            assertThat(request.path("reasoning_effort").asText()).isEqualTo("medium");
            assertThat(request.path("messages").path(0).path("content").path(1).path("type").asText())
                    .isEqualTo("image_url");
            assertThat(request.path("messages").path(0).path("content").path(1).path("image_url").path("url").asText())
                    .isEqualTo("data:image/png;base64,AQID");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsRemoteImageUrlWithoutInliningOssBytes() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ChatCompletionsConnectionProperties connection = new ChatCompletionsConnectionProperties();
            connection.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            connection.setApiKey("test-key");
            ChatCompletionsVisionGateway gateway = new ChatCompletionsVisionGateway(
                    HttpClient.newHttpClient(), new ObjectMapper(), connection, new AiProperties());

            assertThat(gateway.inspect("inspect", "https://oss.example.test/private/image.jpg?signature=test")).isEqualTo("ok");

            JsonNode request = new ObjectMapper().readTree(requestBody.get());
            assertThat(request.path("messages").path(0).path("content").path(1).path("image_url").path("url").asText())
                    .isEqualTo("https://oss.example.test/private/image.jpg?signature=test");
            assertThat(requestBody.get()).doesNotContain("data:image");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsAllVideoFramesThroughChatCompletionsVision() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"model\":\"qwen3.7-plus\",\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7,\"total_tokens\":19},\"choices\":[{\"message\":{\"content\":\"视频摘要\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ChatCompletionsConnectionProperties connection = new ChatCompletionsConnectionProperties();
            connection.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            connection.setApiKey("test-key");
            AiProperties properties = new AiProperties();
            properties.setModel("qwen3.7-plus");
            properties.setTimeout(Duration.ofSeconds(5));
            ChatCompletionsVisionGateway gateway = new ChatCompletionsVisionGateway(
                    HttpClient.newHttpClient(), new ObjectMapper(), connection, properties);

            LlmGateway.ModelReply result = gateway.generate(
                    List.of(new ConversationMessage(ConversationMessage.Role.USER, "上一轮问题")),
                    "概括视频", List.of(
                            new AiImage("image/jpeg", new byte[] {1}, AiImage.Detail.LOW),
                            new AiImage("image/jpeg", new byte[] {2}, AiImage.Detail.LOW)
                    ), new AiRequestBudget(AiRequestBudget.TaskClass.COMPLEX_OR_MULTIMODAL, 300, 0, 0));

            JsonNode request = new ObjectMapper().readTree(requestBody.get());
            assertThat(result.text()).isEqualTo("视频摘要");
            assertThat(result.protocol()).isEqualTo("chat-completions-vision");
            assertThat(result.usage().totalTokens()).isEqualTo(19);
            assertThat(request.path("messages")).hasSize(2);
            assertThat(request.path("messages").path(1).path("content")).hasSize(3);
            assertThat(request.path("messages").path(1).path("content").path(2).path("image_url").path("detail").asText())
                    .isEqualTo("low");
        } finally {
            server.stop(0);
        }
    }
}
