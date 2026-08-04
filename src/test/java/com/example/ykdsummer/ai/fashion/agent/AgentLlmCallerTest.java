package com.example.ykdsummer.ai.fashion.agent;

import com.example.ykdsummer.ai.config.AiProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON 解析回归：Qwen JSON Mode 下的两种兜底策略（直接解析 + 代码块提取），
 * 以及截断 JSON 不再做括号补全（返回 null 交给调用方降级）。
 */
class AgentLlmCallerTest {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    static void shutdownExecutor() {
        EXECUTOR.shutdown();
    }

    @Test
    void parsesDirectJsonOutput() {
        StubChatModel model = new StubChatModel("{\"isFeedback\":true,\"sentiment\":\"MIXED\"}");
        AgentLlmCaller caller = caller(model);

        Map<String, Object> result = caller.callAgent("prompt", "还行但裤子不太行",
                Map.class, 200, Duration.ofSeconds(5));

        assertThat(result).containsEntry("isFeedback", true).containsEntry("sentiment", "MIXED");
    }

    @Test
    void parsesJsonInsideCodeBlock() {
        StubChatModel model = new StubChatModel("```json\n{\"scene\":\"BEACH\"}\n```");
        AgentLlmCaller caller = caller(model);

        Map<String, Object> result = caller.callAgent("prompt", "去海边穿什么",
                Map.class, 200, Duration.ofSeconds(5));

        assertThat(result).containsEntry("scene", "BEACH");
    }

    @Test
    void truncatedJsonReturnsNullInsteadOfRepairingBraces() {
        StubChatModel model = new StubChatModel("{\"suggestions\":[{\"id\":1,\"style\":\"优雅风\"");
        AgentLlmCaller caller = caller(model);

        Map<String, Object> result = caller.callAgent("prompt", "生成方案",
                Map.class, 200, Duration.ofSeconds(5));

        assertThat(result).isNull();
    }

    @Test
    void qwenRequestEnablesJsonObjectResponseFormat() {
        StubChatModel model = new StubChatModel("{}");
        AgentLlmCaller caller = caller(model);

        caller.callAgent("prompt", "生成方案", Map.class, 200, Duration.ofSeconds(5));

        assertThat(model.lastOptions).isInstanceOf(OpenAiChatOptions.class);
        Map<String, Object> extra = ((OpenAiChatOptions) model.lastOptions).getExtraBody();
        assertThat(extra).containsKey("response_format");
        assertThat(extra.get("response_format")).isEqualTo(Map.of("type", "json_object"));
    }

    private static AgentLlmCaller caller(StubChatModel model) {
        AiProperties properties = new AiProperties();
        properties.setFashionModel("qwen3.7-flash");
        return new AgentLlmCaller(model, properties, EXECUTOR);
    }

    /** 固定返回指定文本的 ChatModel，并记录最近一次请求的 options。 */
    private static final class StubChatModel implements ChatModel {
        private final String output;
        private ChatOptions lastOptions;

        private StubChatModel(String output) {
            this.output = output;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastOptions = prompt.getOptions();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.empty();
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().model("stub").build();
        }
    }
}
