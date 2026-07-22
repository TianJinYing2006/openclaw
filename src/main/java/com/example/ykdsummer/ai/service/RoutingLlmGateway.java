package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型网关路由。所有 AI 请求统一由 {@link SpringAiChatCompletionsGateway} 处理。
 *
 * <p>之前存在 Chat Completions 与 Responses 的双网关路由，现已统一收敛
 * 到 Spring AI ChatClient。保留本类作为单一入口，方便后续扩展或切换。</p>
 */
@Service
@Primary
public class RoutingLlmGateway implements LlmGateway {

    private final SpringAiChatCompletionsGateway chatCompletionsGateway;

    public RoutingLlmGateway(SpringAiChatCompletionsGateway chatCompletionsGateway) {
        this.chatCompletionsGateway = chatCompletionsGateway;
    }

    @Override
    public ModelReply generate(
            List<ConversationMessage> history, String prompt,
            List<AiImage> images, List<AiFile> files
    ) {
        return chatCompletionsGateway.generate(history, prompt, images, files);
    }

    @Override
    public ModelReply generate(
            List<ConversationMessage> history, String prompt,
            List<AiImage> images, List<AiFile> files,
            String reasoningEffort
    ) {
        return chatCompletionsGateway.generate(history, prompt, images, files, reasoningEffort);
    }

    @Override
    public ModelReply generate(
            List<ConversationMessage> history, String prompt,
            List<AiImage> images, List<AiFile> files,
            String reasoningEffort, String model
    ) {
        return chatCompletionsGateway.generate(history, prompt, images, files, reasoningEffort, model);
    }
}
