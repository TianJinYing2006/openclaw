package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.tool.BilibiliUserTools;
import com.example.ykdsummer.ai.tool.EpicFreeGamesTools;
import com.example.ykdsummer.ai.tool.QqUserTools;
import com.example.ykdsummer.ai.tool.SteamUserTools;
import com.example.ykdsummer.ai.tool.WeatherTools;
import com.example.ykdsummer.ai.tool.ImageTools;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.ai.tool.SpeechTools;
import com.example.ykdsummer.ai.tool.VoiceSettingsTools;
import com.example.ykdsummer.ai.tool.DocumentTools;
import com.example.ykdsummer.ai.tool.ExternalToolSet;
import com.example.ykdsummer.ai.tool.FileProductionTools;
import com.example.ykdsummer.ai.tool.ConversationMemoryTools;
import com.example.ykdsummer.ai.tool.AssetManagementTools;
import com.example.ykdsummer.ai.tool.AmapTools;
import com.example.ykdsummer.ai.tool.ImageTaskStatusTools;
import com.example.ykdsummer.ai.tool.InformationToolSet;
import com.example.ykdsummer.ai.tool.LocationSearchTools;
import com.example.ykdsummer.ai.tool.WebSearchTools;
import com.example.ykdsummer.ai.tool.PhoneInfoTools;
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
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
    private final ImageTools imageTools;
    private final ToolArtifactCollector artifacts;
    private final SpeechTools speechTools;
    private final VoiceSettingsTools voiceSettingsTools;
    private final DocumentTools documentTools;
    private final FileProductionTools fileProductionTools;
    private final ConversationMemoryTools conversationMemoryTools;
    private final AssetManagementTools assetManagementTools;
    private final ImageTaskStatusTools imageTaskStatusTools;
    private final AiTraceLogger trace;
    private final WebSearchTools webSearchTools;
    private final EpicFreeGamesTools epicFreeGamesTools;
    private final SteamUserTools steamUserTools;
    private final QqUserTools qqUserTools;
    private final BilibiliUserTools bilibiliUserTools;
    private PhoneInfoTools phoneInfoTools;
    private ExternalToolSet externalToolSet;
    private InformationToolSet informationToolSet;
    private AmapTools amapTools;
    private LocationSearchTools locationSearchTools;
    private RealtimeEvidenceAugmenter realtimeEvidenceAugmenter;

    public SpringAiChatCompletionsGateway(
            ChatModel chatModel,
            AiProperties properties,
            WeatherTools weatherTools,
            WebSearchTools webSearchTools,
            EpicFreeGamesTools epicFreeGamesTools,
            SteamUserTools steamUserTools,
            QqUserTools qqUserTools,
            BilibiliUserTools bilibiliUserTools
    ) {
        this(chatModel, properties, weatherTools, null, null, null, null, null, null, null,
                null, null, webSearchTools, epicFreeGamesTools, steamUserTools, qqUserTools,
                bilibiliUserTools, AiTraceLogger.disabled());
    }

    /** 最小测试构造器：只注册天气 Tool。 */
    public SpringAiChatCompletionsGateway(
            ChatModel chatModel, AiProperties properties, WeatherTools weatherTools
    ) {
        this(chatModel, properties, weatherTools, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, AiTraceLogger.disabled());
    }

    @Autowired
    public SpringAiChatCompletionsGateway(
            ChatModel chatModel,
            AiProperties properties,
            WeatherTools weatherTools,
            ImageTools imageTools,
            ToolArtifactCollector artifacts,
            SpeechTools speechTools,
            VoiceSettingsTools voiceSettingsTools,
            DocumentTools documentTools,
            FileProductionTools fileProductionTools,
            ConversationMemoryTools conversationMemoryTools,
            AssetManagementTools assetManagementTools,
            ImageTaskStatusTools imageTaskStatusTools,
            WebSearchTools webSearchTools,
            EpicFreeGamesTools epicFreeGamesTools,
            SteamUserTools steamUserTools,
            QqUserTools qqUserTools,
            BilibiliUserTools bilibiliUserTools,
            AiTraceLogger trace
    ) {
        this.chatClient = ChatClient.create(chatModel);
        this.properties = properties;
        this.weatherTools = weatherTools;
        this.imageTools = imageTools;
        this.artifacts = artifacts;
        this.speechTools = speechTools;
        this.voiceSettingsTools = voiceSettingsTools;
        this.documentTools = documentTools;
        this.fileProductionTools = fileProductionTools;
        this.conversationMemoryTools = conversationMemoryTools;
        this.assetManagementTools = assetManagementTools;
        this.imageTaskStatusTools = imageTaskStatusTools;
        this.trace = trace;
        this.webSearchTools = webSearchTools;
        this.epicFreeGamesTools = epicFreeGamesTools;
        this.steamUserTools = steamUserTools;
        this.qqUserTools = qqUserTools;
        this.bilibiliUserTools = bilibiliUserTools;
        log.info(
                "Spring AI chat gateway initialized, configuredModel={}, reasoningEffort={}",
                properties.getModel(),
                properties.getReasoningEffort()
        );
    }

    @Autowired(required = false)
    void setExternalToolSet(ExternalToolSet externalToolSet) {
        this.externalToolSet = externalToolSet;
    }

    @Autowired(required = false)
    void setInformationToolSet(InformationToolSet informationToolSet) {
        this.informationToolSet = informationToolSet;
    }

    @Autowired(required = false)
    void setAmapTools(AmapTools amapTools) {
        this.amapTools = amapTools;
    }

    @Autowired(required = false)
    void setLocationSearchTools(LocationSearchTools locationSearchTools) {
        this.locationSearchTools = locationSearchTools;
    }

    @Autowired(required = false)
    void setPhoneInfoTools(PhoneInfoTools phoneInfoTools) {
        this.phoneInfoTools = phoneInfoTools;
    }

    @Autowired(required = false)
    void setRealtimeEvidenceAugmenter(RealtimeEvidenceAugmenter realtimeEvidenceAugmenter) {
        this.realtimeEvidenceAugmenter = realtimeEvidenceAugmenter;
    }

    /** 兼容文件生产 Tool 上线前的测试构造器；正式 Spring Bean 会额外注册该 Tool。 */
    public SpringAiChatCompletionsGateway(
            ChatModel chatModel,
            AiProperties properties,
            WeatherTools weatherTools,
            ImageTools imageTools,
            ToolArtifactCollector artifacts,
            SpeechTools speechTools,
            VoiceSettingsTools voiceSettingsTools,
            DocumentTools documentTools,
            ConversationMemoryTools conversationMemoryTools,
            AssetManagementTools assetManagementTools,
            AiTraceLogger trace
    ) {
        this(chatModel, properties, weatherTools, imageTools, artifacts, speechTools, voiceSettingsTools,
                documentTools, null, conversationMemoryTools, assetManagementTools, null,
                null, null, null, null, null, trace);
    }

    /** 兼容图片任务状态 Tool 上线后的测试构造器；平台查询 Tool 在该构造器中不注册。 */
    public SpringAiChatCompletionsGateway(
            ChatModel chatModel, AiProperties properties, WeatherTools weatherTools,
            ImageTools imageTools, ToolArtifactCollector artifacts, SpeechTools speechTools,
            VoiceSettingsTools voiceSettingsTools, DocumentTools documentTools,
            FileProductionTools fileProductionTools, ConversationMemoryTools conversationMemoryTools,
            AssetManagementTools assetManagementTools, ImageTaskStatusTools imageTaskStatusTools,
            AiTraceLogger trace
    ) {
        this(chatModel, properties, weatherTools, imageTools, artifacts, speechTools, voiceSettingsTools,
                documentTools, fileProductionTools, conversationMemoryTools, assetManagementTools, imageTaskStatusTools,
                null, null, null, null, null, trace);
    }

    /** 供现有单元测试和手动构造使用；正式 Spring Bean 会使用带 SpeechTools 的构造器。 */
    public SpringAiChatCompletionsGateway(
            ChatModel chatModel, AiProperties properties, WeatherTools weatherTools,
            ImageTools imageTools, ToolArtifactCollector artifacts, AiTraceLogger trace
    ) {
        this(chatModel, properties, weatherTools, imageTools, artifacts, null, null, null, null, null,
                null, null, null, null, null, null, null, trace);
    }

    /** 兼容 Phase 37 的测试构造器；生产 Bean 会额外注入 DocumentTools。 */
    public SpringAiChatCompletionsGateway(
            ChatModel chatModel, AiProperties properties, WeatherTools weatherTools,
            ImageTools imageTools, ToolArtifactCollector artifacts, SpeechTools speechTools,
            VoiceSettingsTools voiceSettingsTools, AiTraceLogger trace
    ) {
        this(chatModel, properties, weatherTools, imageTools, artifacts, speechTools, voiceSettingsTools,
                null, null, null, null, null, null, null, null, null, null, trace);
    }

    /** 兼容已有测试构造器；生产 Bean 会额外注册 ConversationMemoryTools。 */
    public SpringAiChatCompletionsGateway(
            ChatModel chatModel, AiProperties properties, WeatherTools weatherTools,
            ImageTools imageTools, ToolArtifactCollector artifacts, SpeechTools speechTools,
            VoiceSettingsTools voiceSettingsTools, DocumentTools documentTools, AiTraceLogger trace
    ) {
        this(chatModel, properties, weatherTools, imageTools, artifacts, speechTools, voiceSettingsTools,
                documentTools, null, null, null, null, null, null, null, null, null, trace);
    }

    /** 兼容 Phase 41 的测试构造器；生产 Bean 会额外注册 AssetManagementTools。 */
    public SpringAiChatCompletionsGateway(
            ChatModel chatModel, AiProperties properties, WeatherTools weatherTools,
            ImageTools imageTools, ToolArtifactCollector artifacts, SpeechTools speechTools,
            VoiceSettingsTools voiceSettingsTools, DocumentTools documentTools,
            ConversationMemoryTools conversationMemoryTools, AiTraceLogger trace
    ) {
        this(chatModel, properties, weatherTools, imageTools, artifacts, speechTools, voiceSettingsTools,
                documentTools, null, conversationMemoryTools, null, null, null, null, null, null, null, trace);
    }

    @Override
    public LlmGateway.ModelReply generate(List<ConversationMessage> history, String prompt) {
        return generate("unknown", history, prompt);
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
            List<ToolCallback> callbacks = new ArrayList<>();
            addToolCallbacks(callbacks, weatherTools);
            if (imageTools != null) {
                addToolCallbacks(callbacks, imageTools);
            }
            if (speechTools != null) {
                addToolCallbacks(callbacks, speechTools);
            }
            if (voiceSettingsTools != null) {
                addToolCallbacks(callbacks, voiceSettingsTools);
            }
            if (documentTools != null) {
                addToolCallbacks(callbacks, documentTools);
            }
            if (fileProductionTools != null) {
                addToolCallbacks(callbacks, fileProductionTools);
            }
            if (conversationMemoryTools != null) {
                addToolCallbacks(callbacks, conversationMemoryTools);
            }
            if (assetManagementTools != null) {
                addToolCallbacks(callbacks, assetManagementTools);
            }
            if (imageTaskStatusTools != null) {
                addToolCallbacks(callbacks, imageTaskStatusTools);
            }
            if (webSearchTools != null) {
                addToolCallbacks(callbacks, webSearchTools);
            }
            if (epicFreeGamesTools != null) {
                addToolCallbacks(callbacks, epicFreeGamesTools);
            }
            if (steamUserTools != null) {
                addToolCallbacks(callbacks, steamUserTools);
            }
            if (qqUserTools != null) {
                addToolCallbacks(callbacks, qqUserTools);
            }
            if (bilibiliUserTools != null) {
                addToolCallbacks(callbacks, bilibiliUserTools);
            }
            if (phoneInfoTools != null) {
                addToolCallbacks(callbacks, phoneInfoTools);
            }
            if (externalToolSet != null) {
                addToolCallbacks(callbacks, externalToolSet.toolBeans());
            }
            if (informationToolSet != null) {
                addToolCallbacks(callbacks, informationToolSet.toolBeans());
            }
            if (amapTools != null) {
                addToolCallbacks(callbacks, amapTools);
            }
            if (locationSearchTools != null) {
                addToolCallbacks(callbacks, locationSearchTools);
            }
            trace.toolCatalog(callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());
            log.info(
                    "Spring AI chat completion request, configuredModel={}, reasoningEffort={}, toolCount={}",
                    properties.getModel(),
                    properties.getReasoningEffort(),
                    callbacks.size()
            );
            var request = chatClient.prompt(buildPrompt(history, prompt, outputLimit(budget))).toolCallbacks(callbacks);
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
            log.info(
                    "Spring AI chat completion completed, configuredModel={}, returnedModel={}",
                    properties.getModel(),
                    actualModel
            );
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
        }
    }

    private void addToolCallbacks(List<ToolCallback> callbacks, Object... toolBeans) {
        if (toolBeans == null || toolBeans.length == 0) {
            return;
        }
        Object[] configuredTools = Arrays.stream(toolBeans)
                .filter(Objects::nonNull)
                .toArray();
        if (configuredTools.length == 0) {
            return;
        }
        for (ToolCallback callback : ToolCallbacks.from(configuredTools)) {
            callbacks.add(new TracingToolCallback(callback, trace, realtimeEvidenceAugmenter));
        }
    }

    Prompt buildPrompt(List<ConversationMessage> history, String prompt) {
        return buildPrompt(history, prompt, properties.getMaxCompletionTokens());
    }

    Prompt buildPrompt(List<ConversationMessage> history, String prompt, int maxOutputTokens) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(properties.getSystemPrompt() + RealtimeQueryContext.systemContext(prompt)));
        for (ConversationMessage message : history == null ? List.<ConversationMessage>of() : history) {
            messages.add(message.role() == ConversationMessage.Role.USER
                    ? new UserMessage(message.text())
                    : new AssistantMessage(message.text()));
        }
        messages.add(new UserMessage(prompt));

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .reasoningEffort(properties.getReasoningEffort())
                .store(false);
        if (maxOutputTokens > 0) {
            options.maxCompletionTokens(maxOutputTokens);
        }
        return new Prompt(messages, options.build());
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
