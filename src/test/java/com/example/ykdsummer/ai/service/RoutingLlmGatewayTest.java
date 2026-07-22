package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingLlmGatewayTest {

    @Test
    void delegatesAllCallsToSpringAiCompletions() {
        SpringAiChatCompletionsGateway inner = mock(SpringAiChatCompletionsGateway.class);
        when(inner.generate(anyList(), eq("你好"), anyList(), anyList()))
                .thenReturn(new LlmGateway.ModelReply("简洁回答", "completion-model"));

        RoutingLlmGateway gateway = new RoutingLlmGateway(inner);

        LlmGateway.ModelReply reply = gateway.generate(
                List.of(new ConversationMessage(ConversationMessage.Role.USER, "上一问")),
                "你好",
                List.of(),
                List.of()
        );

        assertThat(reply.model()).isEqualTo("completion-model");
        assertThat(reply.text()).isEqualTo("简洁回答");
        verify(inner).generate(anyList(), eq("你好"), anyList(), anyList());
    }

    @Test
    void delegatesReasoningEffortVariant() {
        SpringAiChatCompletionsGateway inner = mock(SpringAiChatCompletionsGateway.class);
        when(inner.generate(anyList(), eq("分析"), anyList(), anyList(), eq("low")))
                .thenReturn(new LlmGateway.ModelReply("分析完成", "reasoning-model"));

        RoutingLlmGateway gateway = new RoutingLlmGateway(inner);
        LlmGateway.ModelReply reply = gateway.generate(
                List.of(), "分析", List.of(), List.of(), "low");

        assertThat(reply.text()).isEqualTo("分析完成");
        verify(inner).generate(anyList(), eq("分析"), anyList(), anyList(), eq("low"));
    }

    @Test
    void delegatesModelOverrideVariant() {
        SpringAiChatCompletionsGateway inner = mock(SpringAiChatCompletionsGateway.class);
        when(inner.generate(anyList(), eq("hi"), anyList(), anyList(), eq("medium"), eq("gpt-4o")))
                .thenReturn(new LlmGateway.ModelReply("ok", "gpt-4o"));

        RoutingLlmGateway gateway = new RoutingLlmGateway(inner);
        LlmGateway.ModelReply reply = gateway.generate(
                List.of(), "hi", List.of(), List.of(), "medium", "gpt-4o");

        assertThat(reply.text()).isEqualTo("ok");
        verify(inner).generate(anyList(), eq("hi"), anyList(), anyList(), eq("medium"), eq("gpt-4o"));
    }
}
