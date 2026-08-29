package com.wechatbot.fashion.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.wechatbot.fashion.ai.config.AiProperties;
import com.wechatbot.fashion.ai.config.ImageOpenAiClientProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies that the OpenAI-compatible edit adapter retains every reference image in multipart form data. */
class OpenAiImageEditGatewayContractTest {

    @Test
    void sendsEveryReferenceImageAsASeparateMultipartImagePart() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("");
        AtomicReference<String> authorization = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/person.jpg", exchange -> image(exchange, new byte[]{1, 2, 3}, "image/jpeg"));
        server.createContext("/garment.png", exchange -> image(exchange, new byte[]{4, 5, 6}, "image/png"));
        server.createContext("/v1/images/edits", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"data\":[{\"b64_json\":\"AQID\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ImageOpenAiClientProperties image = new ImageOpenAiClientProperties();
            image.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            image.setApiKey("test-key");
            AiProperties ai = new AiProperties();
            ai.setImageModel("gpt-image-2");

            AsyncImageEditGateway.EditResult result = new OpenAiImageEditGateway(image, ai, new ObjectMapper())
                    .edit("Put the garment on the person", List.of(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/person.jpg",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/garment.png"));

            assertThat(result.hasImage()).isTrue();
            assertThat(result.imageBytes()).containsExactly(1, 2, 3);
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            assertThat(occurrences(body.get(), "name=\"image\"")).isEqualTo(2);
            assertThat(body.get()).contains("reference-1.jpg", "reference-2.png", "gpt-image-2");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsLocalDataUrlReferencesForTheSameMultipartEditContract() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/edits", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] response = "{\"data\":[{\"b64_json\":\"AQID\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ImageOpenAiClientProperties image = new ImageOpenAiClientProperties();
            image.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            image.setApiKey("test-key");
            AsyncImageEditGateway.EditResult result = new OpenAiImageEditGateway(image, new AiProperties(), new ObjectMapper())
                    .edit("edit local images", List.of("data:image/png;base64,AQID", "data:image/jpeg;base64,BAUG"));

            assertThat(result.hasImage()).isTrue();
            assertThat(occurrences(body.get(), "name=\"image\"")).isEqualTo(2);
            assertThat(body.get()).contains("reference-1.png", "reference-2.jpg");
        } finally {
            server.stop(0);
        }
    }

    private static int occurrences(String value, String fragment) {
        int count = 0;
        for (int index = value.indexOf(fragment); index >= 0; index = value.indexOf(fragment, index + fragment.length())) {
            count++;
        }
        return count;
    }

    private static void image(com.sun.net.httpserver.HttpExchange exchange, byte[] bytes, String contentType) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
