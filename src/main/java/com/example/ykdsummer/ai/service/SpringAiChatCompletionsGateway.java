package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.tool.WeatherTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * 使用 Spring AI 1.1.8 调用 OpenAI Chat Completions 兼容接口。
 *
 * <p>这条通道只处理纯文本。它把 Java 内存中的 USER/ASSISTANT 历史转换为 Spring AI
 * Message，并在最前面加入统一 system prompt。后续 Agent 和 Tool 可以继续建立在同一个
 * {@link ChatModel} 上，而文件与多模态仍交给 ResponsesGateway。</p>
 */
@Service
public class SpringAiChatCompletionsGateway implements TextChatGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatCompletionsGateway.class);

    private final ChatClient chatClient;
    private final AiProperties properties;
    private final WeatherTools weatherTools;

    public SpringAiChatCompletionsGateway(
            ChatModel chatModel,
            AiProperties properties,
            WeatherTools weatherTools
    ) {
        this.chatClient = ChatClient.create(chatModel);
        this.properties = properties;
        this.weatherTools = weatherTools;
    }

    @Override
    public LlmGateway.ModelReply generate(List<ConversationMessage> history, String prompt) {
        try {
            ChatResponse response = chatClient.prompt(buildPrompt(history, prompt))
                    .tools(weatherTools)
                    .call()
                    .chatResponse();
            String text = extractText(response);
            if (text.isBlank()) {
                throw new AiGatewayException(AiGatewayException.Kind.EMPTY_RESPONSE);
            }
            String actualModel = response.getMetadata() == null || response.getMetadata().getModel() == null
                    || response.getMetadata().getModel().isBlank()
                    ? properties.getModel()
                    : response.getMetadata().getModel();
            log.info("Spring AI chat completion completed, model={}", actualModel);
            return new LlmGateway.ModelReply(text, actualModel);
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            AiGatewayException.Kind kind = isAuthenticationFailure(exception)
                    ? AiGatewayException.Kind.AUTHENTICATION
                    : AiGatewayException.Kind.TEMPORARY_UNAVAILABLE;
            throw new AiGatewayException(kind, exception);
        }
    }

    Prompt buildPrompt(List<ConversationMessage> history, String prompt) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(properties.getSystemPrompt()));
        for (ConversationMessage message : history == null ? List.<ConversationMessage>of() : history) {
            messages.add(message.role() == ConversationMessage.Role.USER
                    ? new UserMessage(message.text())
                    : new AssistantMessage(message.text()));
        }
        messages.add(new UserMessage(prompt));

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .maxCompletionTokens(properties.getMaxCompletionTokens())
                .store(false)
                .build();
        return new Prompt(messages, options);
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text.strip();
    }

    private static boolean isAuthenticationFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof RestClientResponseException responseException) {
                HttpStatusCode status = responseException.getStatusCode();
                if (status.value() == 401 || status.value() == 403) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
