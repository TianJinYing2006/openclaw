package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.orchestration.ToolRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiChatCompletionsGatewayContractTest {

    @Test
    void sendsSystemHistoryAndUserMessagesToChatCompletions() throws IOException {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions",
                exchange -> respond(exchange, requestPath, requestBody, authorization));
        server.start();

        try {
            OpenAiChatModel model = createModel(server);
            AiProperties properties = new AiProperties();
            properties.setModel("gpt-5.6-sol");
            properties.setMaxCompletionTokens(321);
            ToolRegistry toolRegistry = mock(ToolRegistry.class);
            when(toolRegistry.allToolBeans()).thenReturn(new Object[0]);
            SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                    model,
                    properties,
                    toolRegistry
            );

            LlmGateway.ModelReply reply = gateway.generate(
                    List.of(
                            new ConversationMessage(ConversationMessage.Role.USER, "上一问"),
                            new ConversationMessage(ConversationMessage.Role.ASSISTANT, "上一答")
                    ),
                    "这次直接说重点"
            );

            assertThat(reply.text()).isEqualTo("收到，直接说重点。");
            assertThat(reply.model()).isEqualTo("gpt-5.6-sol");
            assertThat(requestPath.get()).isEqualTo("/v1/chat/completions");
            assertThat(authorization.get()).isEqualTo("Bearer test-only");
            assertThat(requestBody.get())
                    .contains("\"model\":\"gpt-5.6-sol\"")
                    .contains("\"store\":false")
                    .contains("\"max_completion_tokens\":321")
                    .contains("\"role\":\"system\"")
                    .contains("像朋友聊天一样自然、直接、简洁地回答")
                    .contains("上一问", "上一答", "这次直接说重点");
        } finally {
            server.stop(0);
        }
    }

    private static OpenAiChatModel createModel(HttpServer server) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-only")
                .completionsPath("/v1/chat/completions")
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("default-model").build())
                .build();
    }

    private static void respond(
            HttpExchange exchange,
            AtomicReference<String> requestPath,
            AtomicReference<String> requestBody,
            AtomicReference<String> authorization
    ) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        sendJson(exchange, """
                {
                  "id":"chatcmpl_test",
                  "object":"chat.completion",
                  "created":1,
                  "model":"gpt-5.6-sol",
                  "choices":[
                    {
                      "index":0,
                      "message":{"role":"assistant","content":"收到，直接说重点。"},
                      "finish_reason":"stop"
                    }
                  ],
                  "usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}
                }
                """);
    }

    private static void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
