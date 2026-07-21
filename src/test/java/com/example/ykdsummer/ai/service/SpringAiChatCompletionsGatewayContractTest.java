package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.tool.WeatherTools;
import com.example.ykdsummer.weather.WeatherInfo;
import com.example.ykdsummer.weather.WeatherService;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiChatCompletionsGatewayContractTest {

    @Test
    void executesToolCallAndSendsToolResultBackToCompletions() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (calls.incrementAndGet() == 1) {
                sendJson(exchange, """
                        {
                          "id":"chatcmpl_tool_request",
                          "object":"chat.completion",
                          "created":1,
                          "model":"gpt-5.6-sol",
                          "choices":[{
                            "index":0,
                            "message":{
                              "role":"assistant",
                              "content":null,
                              "tool_calls":[{
                                "id":"call_weather",
                                "type":"function",
                                "function":{
                                  "name":"get_current_weather",
                                  "arguments":"{\\\"city\\\":\\\"杭州\\\"}"
                                }
                              }]
                            },
                            "finish_reason":"tool_calls"
                          }],
                          "usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}
                        }
                        """);
            } else {
                sendJson(exchange, """
                        {
                          "id":"chatcmpl_tool_result",
                          "object":"chat.completion",
                          "created":2,
                          "model":"gpt-5.6-sol",
                          "choices":[{
                            "index":0,
                            "message":{"role":"assistant","content":"杭州现在小雨，26℃，湿度95%。"},
                            "finish_reason":"stop"
                          }],
                          "usage":{"prompt_tokens":40,"completion_tokens":12,"total_tokens":52}
                        }
                        """);
            }
        });
        server.start();

        try {
            OpenAiChatModel model = createModel(server);
            AiProperties properties = new AiProperties();
            properties.setModel("gpt-5.6-sol");
            WeatherService weatherService = mock(WeatherService.class);
            when(weatherService.getCurrentWeather("杭州")).thenReturn(new WeatherInfo(
                    "浙江省", "杭州市", "小雨", 26,
                    "西南风", "2级", 95, "5 分钟前发布"
            ));
            SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                    model, properties, new WeatherTools(weatherService)
            );

            LlmGateway.ModelReply reply = gateway.generate(List.of(), "杭州现在天气怎么样？");

            assertThat(reply.text()).isEqualTo("杭州现在小雨，26℃，湿度95%。");
            assertThat(calls).hasValue(2);
            assertThat(requestBodies.get(0)).contains("get_current_weather", "杭州现在天气怎么样");
            assertThat(requestBodies.get(1))
                    .contains("\"role\":\"tool\"")
                    .contains("call_weather")
                    .contains("杭州市", "小雨", "temperatureCelsius");
            verify(weatherService).getCurrentWeather("杭州");
        } finally {
            server.stop(0);
        }
    }

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
            SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                    model,
                    properties,
                    new WeatherTools(mock(WeatherService.class))
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
                    .contains("\"tools\"")
                    .contains("get_current_weather")
                    .contains("城市名称")
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
