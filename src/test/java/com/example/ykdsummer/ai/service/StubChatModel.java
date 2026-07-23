package com.example.ykdsummer.ai.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Stub implementation of {@link ChatModel} for unit testing.
 */
public class StubChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        return null;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.empty();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder()
                .model("stub-model")
                .temperature(0.7)
                .build();
    }
}
