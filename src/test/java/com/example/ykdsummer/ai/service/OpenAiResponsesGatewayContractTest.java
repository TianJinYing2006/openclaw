package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.OpenAiClientProperties;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiResponsesGatewayContractTest {

    @Test
    void sendsNativeResponsesPayloadAndReadsAllOutputText() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> respond(exchange, requestPath, requestBody));
        server.start();

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("test-only")
                .timeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();
        try {
            AiProperties properties = new AiProperties();
            properties.setModel("qwen3.7-plus");
            properties.setReasoningEffort("high");
            OpenAiClientProperties connection = new OpenAiClientProperties();
            connection.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            connection.setApiKey("test-only");
            connection.setModel("gpt-5.6-file");
            OpenAiResponsesGateway gateway = new OpenAiResponsesGateway(
                    client, properties, connection, AiTraceLogger.disabled());

            LlmGateway.ModelReply reply = gateway.generate(
                    "responses-user",
                    List.of(
                            new ConversationMessage(ConversationMessage.Role.USER, "前一个问题"),
                            new ConversationMessage(ConversationMessage.Role.ASSISTANT, "前一个回答")
                    ),
                    "看看图片",
                    List.of(
                            new AiImage("image/png", new byte[]{1, 2, 3}),
                            new AiImage("image/jpeg", new byte[]{4, 5, 6}, AiImage.Detail.LOW)
                    ),
                    List.of(
                            new AiFile("sample.xml", "text/xml", "xml-marker".getBytes(StandardCharsets.UTF_8)),
                            new AiFile("Demo.java", "text/x-java", "java-marker".getBytes(StandardCharsets.UTF_8))
                    ),
                    new AiRequestBudget(AiRequestBudget.TaskClass.COMPLEX_OR_MULTIMODAL, 777, 500, 1_277)
            );

            assertThat(reply.text()).isEqualTo("第一段\n第二段");
            assertThat(reply.model()).isEqualTo("gpt-5.6-sol");
            assertThat(reply.protocol()).isEqualTo("responses");
            assertThat(reply.usage()).isEqualTo(AiModelUsage.reported(12, 3, 15));
            assertThat(requestPath.get()).isEqualTo("/v1/responses");
            assertThat(requestBody.get())
                    .contains("\"model\":\"gpt-5.6-file\"")
                    .contains("\"store\":false")
                    .contains("\"max_output_tokens\":777")
                    .contains("\"effort\":\"high\"")
                    .contains("前一个问题", "前一个回答", "看看图片")
                    .contains("data:image/png;base64,AQID")
                    .contains("data:image/jpeg;base64,BAUG")
                    .contains("\"detail\":\"auto\"")
                    .contains("\"detail\":\"low\"")
                    .contains("\"type\":\"input_file\"")
                    .contains("\"filename\":\"sample.xml\"")
                    .contains("data:text/xml;base64,eG1sLW1hcmtlcg==")
                    .contains("\"filename\":\"Demo.java\"")
                    .contains("data:text/x-java;base64,amF2YS1tYXJrZXI=");
        } finally {
            client.close();
            server.stop(0);
        }
    }

    private static void respond(
            HttpExchange exchange,
            AtomicReference<String> requestPath,
            AtomicReference<String> requestBody
    ) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = ("""
                {
                  "id":"resp_test",
                  "object":"response",
                  "created_at":1,
                  "model":"gpt-5.6-sol",
                  "usage":{"input_tokens":12,"output_tokens":3,"total_tokens":15},
                  "output":[
                    {"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[
                      {"type":"output_text","text":"第一段","annotations":[]},
                      {"type":"output_text","text":"第二段","annotations":[]}
                    ]}
                  ],
                  "parallel_tool_calls":false,
                  "tool_choice":"auto",
                  "tools":[]
                }
                """).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
