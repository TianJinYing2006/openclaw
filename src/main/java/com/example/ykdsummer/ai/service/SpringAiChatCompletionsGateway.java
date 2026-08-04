package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.orchestration.BoundedToolCallingManager;
import com.example.ykdsummer.ai.orchestration.ToolRegistry;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * 使用 Spring AI 1.1.8 调用 OpenAI Chat Completions 兼容接口（纯文本通道）。
 *
 * <p>由 {@link RoutingLlmGateway} 在检测到<strong>仅含文字</strong>时路由至此。
 * 它将 Java 内存中的 USER/ASSISTANT 历史转换为 Spring AI Message，并在最前面加入
 * 统一 system prompt。所有工具通过 {@link ToolRegistry} 自动注册。</p>
 *
 * <p>含图片/文件的多模态请求路由至 {@link OpenAiResponsesGateway}（Responses API）。</p>
 */
@Service
public class SpringAiChatCompletionsGateway implements TextChatGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatCompletionsGateway.class);

    private final ChatClient chatClient;
    private final AiProperties properties;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;
    /** 生产环境通过 ToolRegistry 自动发现所有 @Tool Bean；测试环境通过构造函数传入。 */
    private final ToolRegistry toolRegistry;
    private final Object[] testToolBeans;
    /**
     * 生产环境注入的 Agent 轮次守卫。generate() 结束后在 finally 中调用 clearRequest()，
     * 防止池化线程复用时 ThreadLocal 的轮次计数跨请求累积导致后续请求立即触发上限异常。
     * 测试环境为 null，因为测试用 Mock ChatModel 不经过 ToolCallingManager。
     */
    private final BoundedToolCallingManager toolCallingManager;

    /**
     * 生产环境构造器：工具由 {@link ToolRegistry} 自动扫描注册。
     * 注意：ToolRegistry 在 {@code ContextRefreshedEvent} 后才完成扫描，
     * 因此 {@code toolRegistry.allToolBeans()} 在构造时不可用，需在 {@link #generate} 中懒调用。
     */
    @Autowired
    public SpringAiChatCompletionsGateway(
            ChatModel chatModel,
            AiProperties properties,
            ToolRegistry toolRegistry,
            ObjectProvider<BoundedToolCallingManager> toolCallingManagerProvider,
            @Autowired(required = false) ToolArtifactCollector artifactCollector,
            @Autowired(required = false) AiTraceLogger trace
    ) {
        this.chatClient = ChatClient.create(chatModel);
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.testToolBeans = null;
        this.toolCallingManager = toolCallingManagerProvider.getIfAvailable();
        this.artifacts = artifactCollector;
        this.trace = trace != null ? trace : AiTraceLogger.disabled();
    }

    /**
     * 测试环境构造器：工具通过 {@code toolBeans} 数组显式传入，不受 Spring 上下文限制。
     */
    SpringAiChatCompletionsGateway(
            ChatModel chatModel,
            AiProperties properties,
            Object[] toolBeans,
            ToolArtifactCollector artifactCollector,
            AiTraceLogger trace
    ) {
        this.chatClient = ChatClient.create(chatModel);
        this.properties = properties;
        this.toolRegistry = null;
        this.testToolBeans = toolBeans != null ? toolBeans : new Object[0];
        this.toolCallingManager = null;
        this.artifacts = artifactCollector;
        this.trace = trace != null ? trace : AiTraceLogger.disabled();
    }

    @Override
    public LlmGateway.ModelReply generate(List<ConversationMessage> history, String prompt) {
        return generate("unknown", history, prompt, null);
    }

    @Override
    public LlmGateway.ModelReply generate(String userId, List<ConversationMessage> history, String prompt) {
        return generate(userId, history, prompt, null);
    }

    @Override
    public LlmGateway.ModelReply generate(
            String userId, List<ConversationMessage> history, String prompt, AiRequestBudget budget
    ) {
        try {
            if (artifacts != null) {
                artifacts.begin(userId);
            }
            Object[] tools = resolveTools();
            var request = chatClient.prompt(buildPrompt(history, prompt, outputLimit(budget)));
            if (tools.length > 0) {
                request = request.tools(tools);
            }
            ChatResponse response = request
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
            trace.modelReply("Chat Completions", actualModel, text);
            return new LlmGateway.ModelReply(
                    text,
                    actualModel,
                    artifacts == null ? List.of() : artifacts.finish(),
                    extractUsage(response),
                    "chat-completions"
            );
        } catch (AiGatewayException exception) {
            if (artifacts != null) artifacts.discard();
            throw exception;
        } catch (RuntimeException exception) {
            if (artifacts != null) artifacts.discard();
            trace.failure("Chat Completions (/v1/chat/completions)", exception);
            AiGatewayException.Kind kind = isAuthenticationFailure(exception)
                    ? AiGatewayException.Kind.AUTHENTICATION
                    : AiGatewayException.Kind.TEMPORARY_UNAVAILABLE;
            throw new AiGatewayException(kind, exception);
        } finally {
            /*
             * BoundedToolCallingManager 用 ThreadLocal 计数 Agent 轮次。Tomcat 线程池会复用线程，
             * 如果不在请求结束后清理，上一个请求的计数会残留到下一个请求，导致新请求立即触发
             * AgentRoundLimitExceededException。只有经过 .tools().call() 才会触发 ToolCallingManager，
             * 因此只需在此 gateway 清理。
             */
            if (toolCallingManager != null) {
                toolCallingManager.clearRequest();
            }
        }
    }

    /**
     * 解析当前可用的工具列表：
     * <ul>
     *   <li>生产环境：从 {@link ToolRegistry} 懒加载（ContextRefreshedEvent 后可用）</li>
     *   <li>测试环境：使用构造函数传入的 {@code testToolBeans}</li>
     * </ul>
     */
    private Object[] resolveTools() {
        if (toolRegistry != null) {
            return toolRegistry.allToolBeans();
        }
        return testToolBeans;
    }

    Prompt buildPrompt(List<ConversationMessage> history, String prompt) {
        return buildPrompt(history, prompt, properties.getMaxCompletionTokens());
    }

    Prompt buildPrompt(List<ConversationMessage> history, String prompt, int maxOutputTokens) {
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
                .maxCompletionTokens(Math.max(1, maxOutputTokens))
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

    private AiModelUsage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return AiModelUsage.unknown();
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return AiModelUsage.unknown();
        }
        return AiModelUsage.reported(
                nonNegative(usage.getPromptTokens()),
                nonNegative(usage.getCompletionTokens()),
                nonNegative(usage.getTotalTokens())
        );
    }

    private int outputLimit(AiRequestBudget budget) {
        return budget == null ? properties.getMaxCompletionTokens() : budget.maxOutputTokens();
    }

    private static long nonNegative(Integer value) {
        return value == null ? 0L : Math.max(0L, value.longValue());
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
