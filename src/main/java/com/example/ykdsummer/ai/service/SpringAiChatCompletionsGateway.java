package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.orchestration.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 使用 Spring AI 1.1.8 调用 OpenAI Chat Completions 兼容接口的单一路由网关。
 *
 * <p>当前类同时实现 {@link TextChatGateway}（纯文本历史对话）和 {@link LlmGateway}
 * （多模态 + Tool 调用）。外部统一通过 {@link RoutingLlmGateway} 访问。</p>
 *
 * <p>多模态图片通过 Spring AI 的 {@link Media} 机制转换为 Base64 Data URL 发送。
 * Tool 调用通过在 prompt 链中注册 {@code @Tool} Bean 实现。</p>
 */
@Service
public class SpringAiChatCompletionsGateway implements TextChatGateway, LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatCompletionsGateway.class);

    private final ChatClient chatClient;
    private final AiProperties properties;
    private final ToolRegistry toolRegistry;

    public SpringAiChatCompletionsGateway(
            ChatModel chatModel,
            AiProperties properties,
            ToolRegistry toolRegistry
    ) {
        this.chatClient = ChatClient.create(chatModel);
        this.properties = properties;
        this.toolRegistry = toolRegistry;
    }

    // ========== TextChatGateway ==========

    @Override
    public LlmGateway.ModelReply generate(List<ConversationMessage> history, String prompt) {
        return doGenerate(buildMessages(history, prompt, List.of()), properties.getModel(), null);
    }

    // ========== LlmGateway ==========

    @Override
    public LlmGateway.ModelReply generate(
            List<ConversationMessage> history, String prompt,
            List<AiImage> images, List<AiFile> files
    ) {
        return generate(history, prompt, images, files, properties.getReasoningEffort(), null);
    }

    @Override
    public LlmGateway.ModelReply generate(
            List<ConversationMessage> history, String prompt,
            List<AiImage> images, List<AiFile> files,
            String reasoningEffort
    ) {
        return generate(history, prompt, images, files, reasoningEffort, null);
    }

    @Override
    public LlmGateway.ModelReply generate(
            List<ConversationMessage> history, String prompt,
            List<AiImage> images, List<AiFile> files,
            String reasoningEffort, String model
    ) {
        List<Message> messages = buildMessages(history, prompt, images == null ? List.of() : images);
        String effectiveModel = (model != null && !model.isBlank()) ? model : properties.getModel();
        String effort = (reasoningEffort != null && !reasoningEffort.isBlank())
                ? reasoningEffort : properties.getReasoningEffort();
        return doGenerate(messages, effectiveModel, effort);
    }

    // ========== 内部实现 ==========

    private LlmGateway.ModelReply doGenerate(List<Message> messages, String model, String reasoningEffort) {
        try {
            Object[] toolBeans = toolRegistry.allToolBeans();
            log.debug("doGenerate: model={}, tools={}, messages={}", model,
                    toolBeans.length, messages.size());

            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                    .model(model)
                    .maxCompletionTokens(properties.getMaxCompletionTokens())
                    .store(false);
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                optionsBuilder.reasoningEffort(reasoningEffort);
            }
            // 明确设置 tool_choice，鼓励模型使用工具
            optionsBuilder.toolChoice("auto");

            ChatResponse response = chatClient.prompt(new Prompt(messages, optionsBuilder.build()))
                    .tools(toolBeans)
                    .call()
                    .chatResponse();

            String text = extractText(response);
            if (text.isBlank()) {
                if (response != null && response.getResult() != null) {
                    log.warn("Empty response from AI, resultMetadata={}, responseMetadata={}",
                            response.getResult().getMetadata(),
                            response.getMetadata());
                } else {
                    log.warn("Empty response from AI, response=null");
                }
                throw new AiGatewayException(AiGatewayException.Kind.EMPTY_RESPONSE);
            }
            String actualModel = response.getMetadata() == null || response.getMetadata().getModel() == null
                    || response.getMetadata().getModel().isBlank()
                    ? model
                    : response.getMetadata().getModel();
            log.info("AI completion completed, model={}", actualModel);
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

    /**
     * 构造消息列表：SystemPrompt + 历史 + 当前用户消息（含图片）。
     */
    private List<Message> buildMessages(
            List<ConversationMessage> history, String prompt, List<AiImage> images
    ) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(properties.getSystemPrompt()));

        for (ConversationMessage msg : history == null ? List.<ConversationMessage>of() : history) {
            messages.add(msg.role() == ConversationMessage.Role.USER
                    ? new UserMessage(msg.text())
                    : new AssistantMessage(msg.text()));
        }

        messages.add(buildUserMessage(prompt, images));
        return messages;
    }

    /**
     * 构建用户消息。有图片时创建附带 Media 的多模态 UserMessage，否则为纯文本。
     */
    private static Message buildUserMessage(String prompt, List<AiImage> images) {
        if (images == null || images.isEmpty()) {
            return new UserMessage(prompt);
        }
        List<Media> mediaList = images.stream()
                .map(img -> new Media(
                        MimeTypeUtils.parseMimeType(img.mediaType()),
                        new ByteArrayResource(img.bytes())))
                .toList();
        return UserMessage.builder()
                .text(prompt)
                .media(mediaList)
                .metadata(Map.of())
                .build();
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return "";
        }
        // Spring AI 工具调用可能产生多轮回复（AI 推理文本 + 工具调用后的最终文本），
        // 合并所有结果文本为一段，避免同一轮对话出现两段独立回复
        String combined = response.getResults().stream()
                .filter(Objects::nonNull)
                .map(Generation::getOutput)
                .filter(Objects::nonNull)
                .filter(output -> output instanceof AssistantMessage)
                .map(output -> ((AssistantMessage) output).getText())
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
        return combined.isBlank() ? "" : combined;
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
