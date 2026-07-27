package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.OpenAiClientProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingLlmGatewayTest {

    @Test
    void routesPlainTextToSpringAiCompletions() {
        RecordingTextGateway text = new RecordingTextGateway();
        RecordingResponsesGateway responses = new RecordingResponsesGateway();
        RoutingLlmGateway gateway = new RoutingLlmGateway(text, responses);

        LlmGateway.ModelReply reply = gateway.generate(
                List.of(new ConversationMessage(ConversationMessage.Role.USER, "上一问")),
                "你好",
                List.of(),
                List.of()
        );

        assertThat(reply.model()).isEqualTo("completion-model");
        assertThat(text.calls).isEqualTo(1);
        assertThat(text.lastPrompt).isEqualTo("你好");
        assertThat(responses.calls).isZero();
    }

    @Test
    void routesImagesToChatCompletionsVisionAndBlocksRawFilesWhenResponsesAreDisabled() {
        RecordingTextGateway text = new RecordingTextGateway();
        RecordingResponsesGateway responses = new RecordingResponsesGateway();
        RecordingVisionGateway vision = new RecordingVisionGateway();
        RoutingLlmGateway gateway = new RoutingLlmGateway(
                text, responses, vision, AiTraceLogger.disabled(), new AiProperties());

        gateway.generate(List.of(), "看图", List.of(new AiImage("image/png", new byte[]{1})), List.of());
        gateway.generate(List.of(), "看文件", List.of(),
                List.of(new AiFile("a.txt", "text/plain", new byte[]{2})));

        assertThat(text.calls).isZero();
        assertThat(vision.calls).isEqualTo(1);
        assertThat(responses.calls).isZero();
    }

    @Test
    void routesRawFilesToResponsesOnlyWhenExplicitlyEnabled() {
        RecordingTextGateway text = new RecordingTextGateway();
        RecordingResponsesGateway responses = new RecordingResponsesGateway();
        AiProperties properties = new AiProperties();
        properties.setResponsesEnabled(true);
        RoutingLlmGateway gateway = new RoutingLlmGateway(
                text, responses, null, AiTraceLogger.disabled(), properties, configuredResponses());

        gateway.generate(List.of(), "读取原始文件", List.of(), List.of(new AiFile("scan.pdf", "application/pdf", new byte[]{1})));

        assertThat(text.calls).isZero();
        assertThat(responses.calls).isEqualTo(1);
    }

    @Test
    void blocksRawFilesWhenResponsesAreEnabledButItsConnectionIsIncomplete() {
        RecordingTextGateway text = new RecordingTextGateway();
        RecordingResponsesGateway responses = new RecordingResponsesGateway();
        AiProperties properties = new AiProperties();
        properties.setResponsesEnabled(true);
        RoutingLlmGateway gateway = new RoutingLlmGateway(
                text, responses, null, AiTraceLogger.disabled(), properties, new OpenAiClientProperties());

        LlmGateway.ModelReply reply = gateway.generate(
                List.of(), "读取原始文件", List.of(), List.of(new AiFile("scan.pdf", "application/pdf", new byte[]{1})));

        assertThat(reply.protocol()).isEqualTo("local-file-fallback");
        assertThat(reply.text()).isEqualTo(RoutingLlmGateway.RESPONSES_UNAVAILABLE_REPLY);
        assertThat(responses.calls).isZero();
    }

    @Test
    void keepsPureTextOnChatCompletionsEvenWhenOldCallerSpecifiesReasoning() {
        RecordingTextGateway text = new RecordingTextGateway();
        RecordingResponsesGateway responses = new RecordingResponsesGateway();
        RoutingLlmGateway gateway = new RoutingLlmGateway(text, responses);

        gateway.generate(List.of(), "分析已提取的文档文字", List.of(), List.of(), "low");

        assertThat(text.calls).isEqualTo(1);
        assertThat(responses.calls).isZero();
    }

    private static final class RecordingTextGateway implements TextChatGateway {
        private int calls;
        private String lastPrompt;

        @Override
        public LlmGateway.ModelReply generate(List<ConversationMessage> history, String prompt) {
            calls++;
            lastPrompt = prompt;
            return new LlmGateway.ModelReply("简洁回答", "completion-model");
        }
    }

    private static OpenAiClientProperties configuredResponses() {
        OpenAiClientProperties connection = new OpenAiClientProperties();
        connection.setBaseUrl("https://responses.example/v1");
        connection.setApiKey("responses-key");
        connection.setModel("file-model");
        return connection;
    }

    private static final class RecordingResponsesGateway implements ResponsesGateway {
        private int calls;
        private String lastReasoningEffort;

        @Override
        public ModelReply generate(List<ConversationMessage> history, String prompt,
                                   List<AiImage> images, List<AiFile> files) {
            calls++;
            return new ModelReply("多模态回答", "responses-model");
        }

        @Override
        public ModelReply generate(List<ConversationMessage> history, String prompt,
                                   List<AiImage> images, List<AiFile> files, String reasoningEffort) {
            calls++;
            lastReasoningEffort = reasoningEffort;
            return new ModelReply("任务回答", "responses-model");
        }
    }

    private static final class RecordingVisionGateway implements VisionChatGateway {
        private int calls;

        @Override
        public LlmGateway.ModelReply generate(
                List<ConversationMessage> history, String prompt, List<AiImage> images, AiRequestBudget budget
        ) {
            calls++;
            return new LlmGateway.ModelReply("视觉回答", "vision-model");
        }
    }
}
