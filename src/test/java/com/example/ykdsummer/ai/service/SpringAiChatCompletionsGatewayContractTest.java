package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.orchestration.ScheduledAgentExecutionContext;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.tool.WeatherTools;
import com.example.ykdsummer.ai.tool.ImageTools;
import com.example.ykdsummer.ai.tool.SpeechTools;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.ai.tool.VoiceSettingsTools;
import com.example.ykdsummer.ai.tool.DocumentTools;
import com.example.ykdsummer.ai.tool.FileProductionTools;
import com.example.ykdsummer.ai.tool.ConversationMemoryTools;
import com.example.ykdsummer.ai.tool.AssetManagementTools;
import com.example.ykdsummer.ai.tool.ImageTaskStatusTools;
import com.example.ykdsummer.bot.document.DocumentTextExtractor;
import com.example.ykdsummer.bot.document.DocumentRenderer;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore;
import com.example.ykdsummer.bot.audio.TextToSpeechService;
import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import com.example.ykdsummer.bot.file.FileSessionService;
import com.example.ykdsummer.fashion.application.FashionCoreService;
import com.example.ykdsummer.fashion.application.FashionPersonTemplateService;
import com.example.ykdsummer.fashion.application.FashionWardrobeIngestionService;
import com.example.ykdsummer.fashion.tool.FashionPersonTemplateTools;
import com.example.ykdsummer.fashion.tool.FashionTools;
import com.example.ykdsummer.fashion.tool.FashionWardrobeIntakeTools;
import com.example.ykdsummer.reminder.application.ReminderService;
import com.example.ykdsummer.reminder.tool.ChinaTimeTools;
import com.example.ykdsummer.reminder.tool.ReminderTools;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiChatCompletionsGatewayContractTest {

    @Test
    void scheduledAgentTaskDoesNotReceiveReminderManagementTools() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendJson(exchange, """
                    {"id":"scheduled-task","object":"chat.completion","created":1,"model":"gpt-5.6-sol",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"已完成。"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                    """);
        });
        server.start();

        try {
            ToolArtifactCollector collector = new ToolArtifactCollector();
            SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                    createModel(server), new AiProperties(), new WeatherTools(mock(WeatherService.class)),
                    null, collector, AiTraceLogger.disabled());
            gateway.setReminderTools(new ReminderTools(mock(ReminderService.class), collector, AiTraceLogger.disabled()));
            gateway.setChinaTimeTools(new ChinaTimeTools(AiTraceLogger.disabled()));

            try (ScheduledAgentExecutionContext.Scope ignored = ScheduledAgentExecutionContext.enter()) {
                assertThat(gateway.generate("user", List.of(), "现在执行定时任务").text()).isEqualTo("已完成。");
            }

            assertThat(requestBody.get()).contains("get_current_weather", "get_current_china_time");
            assertThat(requestBody.get()).doesNotContain("create_wechat_reminder", "create_scheduled_agent_task",
                    "list_wechat_reminders", "cancel_wechat_reminder");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void imageToolCallReportsTaskSubmissionWithoutReturningAnAttachment() throws IOException {
        byte[] png = {9, 8, 7, 6};
        AtomicInteger calls = new AtomicInteger();
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (calls.incrementAndGet() == 1) {
                sendJson(exchange, """
                        {
                          "id":"chatcmpl_image_tool_request",
                          "object":"chat.completion",
                          "created":1,
                          "model":"gpt-5.6-sol",
                          "choices":[{"index":0,"message":{"role":"assistant","content":null,
                            "tool_calls":[{"id":"call_generate_image","type":"function","function":
                              {"name":"generate_image","arguments":"{\\\"prompt\\\":\\\"一只在草地上的小狗\\\"}"}}]},"finish_reason":"tool_calls"}],
                          "usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}
                        }
                        """);
            } else {
                sendJson(exchange, """
                        {
                          "id":"chatcmpl_image_tool_result",
                          "object":"chat.completion",
                          "created":2,
                          "model":"gpt-5.6-sol",
                          "choices":[{"index":0,"message":{"role":"assistant","content":"图片已经为你生成。"},"finish_reason":"stop"}],
                          "usage":{"prompt_tokens":40,"completion_tokens":12,"total_tokens":52}
                        }
                        """);
            }
        });
        server.start();

        try {
            AiImageGenerationService imageService = mock(AiImageGenerationService.class);
            when(imageService.generate(eq("image-user"), anyString()))
                    .thenReturn(AiImageGenerationService.Result.image(png));
            LocalImageAssetStore failingStore = new LocalImageAssetStore() {
                @Override
                public StoredImage saveGenerated(String userId, String prompt, byte[] bytes, String remoteUrl) {
                    throw new IllegalStateException("OSS unavailable");
                }
            };
            ToolArtifactCollector collector = new ToolArtifactCollector();
            ImageTools imageTools = new ImageTools(imageService, failingStore, collector);
            SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                    createModel(server), new AiProperties(), new WeatherTools(mock(WeatherService.class)),
                    imageTools, collector, AiTraceLogger.disabled());

            LlmGateway.ModelReply reply = gateway.generate("image-user", List.of(), "给我生成一张小狗图片");

            assertThat(reply.text()).isEqualTo("图片已经为你生成。");
            assertThat(reply.artifacts()).isEmpty();
            assertThat(calls).hasValue(2);
            assertThat(requestBodies.get(1)).contains("\"role\":\"tool\"", "图片任务已提交后台", "任务编号");
            verify(imageService).generate(eq("image-user"), anyString());
        } finally {
            server.stop(0);
        }
    }

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
            assertThat(reply.protocol()).isEqualTo("chat-completions");
            // Spring AI 会把工具调用前、后的两次模型请求 usage 汇总为本轮总用量：28 + 52 = 80。
            assertThat(reply.usage()).isEqualTo(AiModelUsage.reported(60, 20, 80));
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
            ToolArtifactCollector collector = new ToolArtifactCollector();
            TtsVoiceSelectionService voices = mock(TtsVoiceSelectionService.class);
            ConversationMemoryTools memoryTools = new ConversationMemoryTools(
                    new AiChatService(properties, (history, prompt, images, files) -> new LlmGateway.ModelReply("", "test")),
                    new FileSessionService(), new LocalImageAssetStore(), new LocalDocumentAssetStore(), collector
            );
            ImageTaskStatusStore taskStore = new ImageTaskStatusStore();
            ImageTools imageTools = new ImageTools(mock(AiImageGenerationService.class), mock(LocalImageAssetStore.class),
                    collector, ImageInspectionService.unavailable(), taskStore, AiTraceLogger.disabled());
            SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                    model,
                    properties,
                    new WeatherTools(mock(WeatherService.class)),
                    imageTools,
                    collector,
                    new SpeechTools(mock(TextToSpeechService.class), voices, collector),
                    new VoiceSettingsTools(voices, collector),
                    new DocumentTools(new LocalDocumentAssetStore(), new DocumentTextExtractor(), collector),
                    new FileProductionTools(new LocalDocumentAssetStore(), collector),
                    memoryTools,
                    new AssetManagementTools(new LocalImageAssetStore(), new LocalDocumentAssetStore(), collector),
                    new ImageTaskStatusTools(taskStore, imageTools, collector),
                    AiTraceLogger.disabled()
            );
            gateway.setFashionTools(new FashionTools(mock(FashionCoreService.class), collector, AiTraceLogger.disabled()));
            gateway.setFashionPersonTemplateTools(new FashionPersonTemplateTools(
                    mock(FashionPersonTemplateService.class), collector, AiTraceLogger.disabled()));
            gateway.setFashionWardrobeIntakeTools(new FashionWardrobeIntakeTools(
                    mock(FashionWardrobeIngestionService.class), collector, AiTraceLogger.disabled()));

            LlmGateway.ModelReply reply = gateway.generate(
                    "chat-user",
                    List.of(
                            new ConversationMessage(ConversationMessage.Role.USER, "上一问"),
                            new ConversationMessage(ConversationMessage.Role.ASSISTANT, "上一答")
                    ),
                    "这次直接说重点",
                    new AiRequestBudget(AiRequestBudget.TaskClass.STANDARD, 777, 200, 977)
            );

            assertThat(reply.text()).isEqualTo("收到，直接说重点。");
            assertThat(reply.model()).isEqualTo("gpt-5.6-sol");
            assertThat(requestPath.get()).isEqualTo("/v1/chat/completions");
            assertThat(authorization.get()).isEqualTo("Bearer test-only");
            assertThat(requestBody.get())
                    .contains("\"model\":\"gpt-5.6-sol\"")
                    .contains("\"store\":false")
                    .contains("\"max_completion_tokens\":777")
                    .contains("\"tools\"")
                    .contains("get_current_weather")
                    .contains("generate_image")
                    .contains("synthesize_speech")
                    .contains("set_voice", "get_current_voice", "list_voice_options", "reset_voice")
                    .contains("get_current_image", "inspect_image", "create_image_revision", "restore_image_version")
                    .contains("create_document", "get_current_document", "replace_document_content", "restore_document_version")
                    .contains("produce_file")
                    .contains("clear_current_memory")
                    .contains("list_recent_assets", "select_asset", "describe_asset", "resend_asset")
                    .contains("get_running_tasks", "check_image_task", "retry_last_image_task")
                    .contains("get_fashion_profile", "search_wardrobe", "add_wardrobe_item")
                    .contains("styleTags", "fitCode", "patternCode", "seasonTags", "occasionTags", "material")
                    .contains("save_person_tryon_template", "list_person_tryon_templates", "select_person_tryon_template")
                    .contains("analyze_wardrobe_photo", "update_wardrobe_candidate_labels", "submit_garment_cutout",
                            "retry_garment_cutout", "confirm_wardrobe_candidate")
                    .contains("城市名称")
                    .contains("\"role\":\"system\"")
                    .contains("像朋友聊天一样自然、直接、简洁地回答")
                    .contains("上一问", "上一答", "这次直接说重点");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void canExecuteDocumentToolForPreviouslyImportedPdf() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (calls.incrementAndGet() == 1) {
                sendJson(exchange, """
                        {
                          "id":"chatcmpl_document_tool_request",
                          "object":"chat.completion",
                          "created":1,
                          "model":"gpt-5.6-sol",
                          "choices":[{"index":0,"message":{"role":"assistant","content":null,
                            "tool_calls":[{"id":"call_current_document","type":"function","function":
                              {"name":"get_current_document","arguments":"{}"}}]},"finish_reason":"tool_calls"}],
                          "usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}
                        }
                        """);
            } else {
                sendJson(exchange, """
                        {
                          "id":"chatcmpl_document_tool_result",
                          "object":"chat.completion",
                          "created":2,
                          "model":"gpt-5.6-sol",
                          "choices":[{"index":0,"message":{"role":"assistant","content":"这份 PDF 已读取，可以继续修改。"},"finish_reason":"stop"}],
                          "usage":{"prompt_tokens":40,"completion_tokens":12,"total_tokens":52}
                        }
                        """);
            }
        });
        server.start();

        try {
            ToolArtifactCollector collector = new ToolArtifactCollector();
            LocalDocumentAssetStore store = new LocalDocumentAssetStore();
            byte[] pdf = new DocumentRenderer().render("pdf", "PDF 唯一标记：工具链测试");
            store.importUploaded("pdf-user", new com.example.ykdsummer.ai.model.AiFile(
                    "tool-test.pdf", "application/pdf", pdf));
            SpringAiChatCompletionsGateway gateway = new SpringAiChatCompletionsGateway(
                    createModel(server), new AiProperties(), new WeatherTools(mock(WeatherService.class)),
                    null, collector, null, null,
                    new DocumentTools(store, new DocumentTextExtractor(), collector), AiTraceLogger.disabled());

            LlmGateway.ModelReply reply = gateway.generate("pdf-user", List.of(), "请查看我刚上传的 PDF");

            assertThat(reply.text()).contains("PDF 已读取");
            assertThat(calls).hasValue(2);
            assertThat(requestBodies.get(0)).contains("get_current_document");
            assertThat(requestBodies.get(1)).contains("\"role\":\"tool\"", "当前文档", "PDF 唯一标记：工具链测试");
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
