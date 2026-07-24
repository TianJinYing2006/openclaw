package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Verifies the active Spring AI Chat Completions path against a configured provider. */
@SpringBootTest(properties = "ilink.enabled=false")
@EnabledIfEnvironmentVariable(named = "CHAT_LIVE_TEST", matches = "true")
class SpringAiChatCompletionsGatewayLiveTest {

    @Autowired
    private SpringAiChatCompletionsGateway gateway;

    @Test
    @Timeout(90)
    void configuredProviderReturnsAChatReply() {
        LlmGateway.ModelReply reply = gateway.generate("chat-live-test", List.of(), "Only reply with OK.");

        assertThat(reply.text()).isNotBlank();
        assertThat(reply.protocol()).isEqualTo("chat-completions");
    }
}
