package com.wechatbot.fashion.ai.service;

import com.wechatbot.fashion.ai.config.AiProperties;
import com.wechatbot.fashion.ai.config.OpenAiClientProperties;
import com.wechatbot.fashion.ai.model.AiFile;
import com.wechatbot.fashion.ai.model.AiImage;
import com.wechatbot.fashion.ai.model.ConversationMessage;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 在 Chat Completions 与 Responses 之间做唯一、集中、可测试的协议选择。
 *
 * <ul>
 *   <li>普通纯文本四参数调用：Spring AI Chat Completions。</li>
 *   <li>带图片但没有原始文件：OpenAI-compatible Chat Completions vision。</li>
 *   <li>带原始文件：仅在独立的 Responses 连接完整配置后使用 OpenAI Responses。</li>
 * </ul>
 */
@Service
@Primary
public class RoutingLlmGateway implements LlmGateway {

    static final String RESPONSES_UNAVAILABLE_REPLY = "当前未配置可用的原始文件解析模型。请让管理员配置支持 Responses API 的文件模型后重试。";
    static final String VISION_UNAVAILABLE_REPLY = "当前模型服务未启用图片视觉识别，请联系管理员检查视觉模型配置。";

    private final TextChatGateway textChatGateway;
    private final ResponsesGateway responsesGateway;
    private final VisionChatGateway visionChatGateway;
    private final AiTraceLogger trace;
    private final AiProperties properties;
    private final OpenAiClientProperties responsesConnection;

    public RoutingLlmGateway(TextChatGateway textChatGateway, ResponsesGateway responsesGateway) {
        this(textChatGateway, responsesGateway, null, AiTraceLogger.disabled(), new AiProperties(), new OpenAiClientProperties());
    }

    public RoutingLlmGateway(
            TextChatGateway textChatGateway,
            ResponsesGateway responsesGateway,
            AiTraceLogger trace
    ) {
        this(textChatGateway, responsesGateway, null, trace, new AiProperties(), new OpenAiClientProperties());
    }

    /** Compatibility constructor for tests and manual callers that do not configure Responses. */
    public RoutingLlmGateway(
            TextChatGateway textChatGateway,
            ResponsesGateway responsesGateway,
            VisionChatGateway visionChatGateway,
            AiTraceLogger trace,
            AiProperties properties
    ) {
        this(textChatGateway, responsesGateway, visionChatGateway, trace, properties, new OpenAiClientProperties());
    }

    @Autowired
    public RoutingLlmGateway(
            TextChatGateway textChatGateway,
            ResponsesGateway responsesGateway,
            VisionChatGateway visionChatGateway,
            AiTraceLogger trace,
            AiProperties properties,
            OpenAiClientProperties responsesConnection
    ) {
        this.textChatGateway = textChatGateway;
        this.responsesGateway = responsesGateway;
        this.visionChatGateway = visionChatGateway;
        this.trace = trace;
        this.properties = properties;
        this.responsesConnection = responsesConnection == null ? new OpenAiClientProperties() : responsesConnection;
    }

    @Override
    public ModelReply generate(String userId, List<ConversationMessage> history, String prompt,
                               List<AiImage> images, List<AiFile> files) {
        if (isEmpty(images) && isEmpty(files)) {
            trace.route(userId, "Chat Completions (/v1/chat/completions)", "本轮只有文字", 0, 0);
            return textChatGateway.generate(userId, history, prompt);
        }
        if (isEmpty(files)) {
            return visualReply(userId, history, prompt, images, null);
        }
        return fileReply(userId, history, prompt, images, files, null);
    }

    @Override
    public ModelReply generate(String userId, List<ConversationMessage> history, String prompt,
                               List<AiImage> images, List<AiFile> files, AiRequestBudget budget) {
        if (isEmpty(images) && isEmpty(files)) {
            trace.route(userId, "Chat Completions (/v1/chat/completions)", "本轮只有文字", 0, 0);
            return textChatGateway.generate(userId, history, prompt, budget);
        }
        if (isEmpty(files)) {
            return visualReply(userId, history, prompt, images, budget);
        }
        return fileReply(userId, history, prompt, images, files, budget);
    }

    @Override
    public ModelReply generate(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            List<AiFile> files
    ) {
        if (isEmpty(images) && isEmpty(files)) {
            trace.route("Chat Completions (/v1/chat/completions)", "本轮只有文字", 0, 0);
            return textChatGateway.generate(history, prompt);
        }
        if (isEmpty(files)) {
            return visualReply(null, history, prompt, images, null);
        }
        return fileReply(null, history, prompt, images, files, null);
    }

    @Override
    public ModelReply generate(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            List<AiFile> files,
            String reasoningEffort
    ) {
        if (isEmpty(images) && isEmpty(files)) {
            trace.route("Chat Completions (/v1/chat/completions)",
                    "纯文字请求使用当前模型的 reasoning-effort", 0, 0);
            return textChatGateway.generate(history, prompt);
        }
        if (isEmpty(files)) {
            return visualReply(null, history, prompt, images, null);
        }
        if (!responsesAvailable()) {
            return responsesDisabled(null, images, files);
        }
        trace.route(
                "Responses (/v1/responses)",
                "已显式启用 Responses，业务指定 reasoning effort=" + reasoningEffort,
                size(images),
                size(files)
        );
        return responsesGateway.generate(history, prompt, images, files, reasoningEffort);
    }

    private ModelReply visualReply(String userId, List<ConversationMessage> history, String prompt, List<AiImage> images,
                                   AiRequestBudget budget) {
        if (visionChatGateway == null) {
            trace.route(userId, "Local fallback", "视觉网关未配置", size(images), 0);
            return localReply(VISION_UNAVAILABLE_REPLY, "local-vision-fallback");
        }
        trace.route(userId, "Chat Completions (/v1/chat/completions)", "本轮包含图片，使用兼容视觉输入", size(images), 0);
        return visionChatGateway.generate(history, prompt, images, budget);
    }

    private ModelReply fileReply(String userId, List<ConversationMessage> history, String prompt,
                                 List<AiImage> images, List<AiFile> files, AiRequestBudget budget) {
        if (!responsesAvailable()) {
            return responsesDisabled(userId, images, files);
        }
        trace.route(userId, "Responses (/v1/responses)", "已显式启用 Responses，处理原始文件", size(images), size(files));
        return budget == null
                ? responsesGateway.generate(history, prompt, images, files)
                : responsesGateway.generate(userId, history, prompt, images, files, budget);
    }

    private ModelReply responsesDisabled(String userId, List<AiImage> images, List<AiFile> files) {
        String reason = properties.isResponsesEnabled()
                ? "Responses 已开启但独立连接未完整配置"
                : "当前模型未启用 Responses 原始文件能力";
        trace.route(userId, "Local fallback", reason, size(images), size(files));
        return localReply(RESPONSES_UNAVAILABLE_REPLY, "local-file-fallback");
    }

    private boolean responsesAvailable() {
        return properties.isResponsesEnabled() && responsesConnection.isConfigured();
    }

    private static ModelReply localReply(String text, String protocol) {
        return new ModelReply(text, "local-capability", List.of(), AiModelUsage.unknown(), protocol);
    }

    private static boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
